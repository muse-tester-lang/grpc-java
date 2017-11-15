/*
 * Copyright 2017, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import static io.opencensus.tags.unsafe.ContextUtils.TAG_CONTEXT_KEY;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isNull;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ClientStreamTracer;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.internal.testing.StatsTestUtils;
import io.grpc.internal.testing.StatsTestUtils.FakeStatsRecorder;
import io.grpc.internal.testing.StatsTestUtils.FakeTagContextBinarySerializer;
import io.grpc.internal.testing.StatsTestUtils.FakeTagger;
import io.grpc.internal.testing.StatsTestUtils.MockableSpan;
import io.grpc.testing.GrpcServerRule;
import io.opencensus.contrib.grpc.metrics.RpcMeasureConstants;
import io.opencensus.tags.TagContext;
import io.opencensus.tags.TagValue;
import io.opencensus.tags.Tags;
import io.opencensus.trace.EndSpanOptions;
import io.opencensus.trace.NetworkEvent;
import io.opencensus.trace.NetworkEvent.Type;
import io.opencensus.trace.Span;
import io.opencensus.trace.SpanBuilder;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.propagation.BinaryFormat;
import io.opencensus.trace.propagation.SpanContextParseException;
import io.opencensus.trace.unsafe.ContextUtils;
import java.io.InputStream;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test for {@link CensusStatsModule} and {@link CensusTracingModule}.
 */
@RunWith(JUnit4.class)
public class CensusModulesTest {
  private static final CallOptions.Key<String> CUSTOM_OPTION =
      CallOptions.Key.of("option1", "default");
  private static final CallOptions CALL_OPTIONS =
      CallOptions.DEFAULT.withOption(CUSTOM_OPTION, "customvalue");

  private static class StringInputStream extends InputStream {
    final String string;

    StringInputStream(String string) {
      this.string = string;
    }

    @Override
    public int read() {
      // InProcessTransport doesn't actually read bytes from the InputStream.  The InputStream is
      // passed to the InProcess server and consumed by MARSHALLER.parse().
      throw new UnsupportedOperationException("Should not be called");
    }
  }

  private static final MethodDescriptor.Marshaller<String> MARSHALLER =
      new MethodDescriptor.Marshaller<String>() {
        @Override
        public InputStream stream(String value) {
          return new StringInputStream(value);
        }

        @Override
        public String parse(InputStream stream) {
          return ((StringInputStream) stream).string;
        }
      };

  private final MethodDescriptor<String, String> method =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNKNOWN)
          .setRequestMarshaller(MARSHALLER)
          .setResponseMarshaller(MARSHALLER)
          .setFullMethodName("package1.service2/method3")
          .build();
  private final MethodDescriptor<String, String> sampledMethod =
      method.toBuilder().setSampledToLocalTracing(true).build();

  private final FakeClock fakeClock = new FakeClock();
  private final FakeTagger tagger = new FakeTagger();
  private final FakeTagContextBinarySerializer tagCtxSerializer =
      new FakeTagContextBinarySerializer();
  private final FakeStatsRecorder statsRecorder = new FakeStatsRecorder();
  private final Random random = new Random(1234);
  private final Span fakeClientParentSpan = MockableSpan.generateRandomSpan(random);
  private final Span spyClientSpan = spy(MockableSpan.generateRandomSpan(random));
  private final SpanContext fakeClientSpanContext = spyClientSpan.getContext();
  private final Span spyServerSpan = spy(MockableSpan.generateRandomSpan(random));
  private final byte[] binarySpanContext = new byte[]{3, 1, 5};
  private final SpanBuilder spyClientSpanBuilder = spy(new MockableSpan.Builder());
  private final SpanBuilder spyServerSpanBuilder = spy(new MockableSpan.Builder());

  @Rule
  public final GrpcServerRule grpcServerRule = new GrpcServerRule().directExecutor();

  @Mock
  private Tracer tracer;
  @Mock
  private BinaryFormat mockTracingPropagationHandler;
  @Mock
  private ClientCall.Listener<String> mockClientCallListener;
  @Mock
  private ServerCall.Listener<String> mockServerCallListener;
  @Captor
  private ArgumentCaptor<CallOptions> callOptionsCaptor;
  @Captor
  private ArgumentCaptor<ClientCall.Listener<String>> clientCallListenerCaptor;
  @Captor
  private ArgumentCaptor<Status> statusCaptor;
  @Captor
  private ArgumentCaptor<NetworkEvent> networkEventCaptor;

  private CensusStatsModule censusStats;
  private CensusTracingModule censusTracing;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    when(spyClientSpanBuilder.startSpan()).thenReturn(spyClientSpan);
    when(tracer.spanBuilderWithExplicitParent(anyString(), any(Span.class)))
        .thenReturn(spyClientSpanBuilder);
    when(spyServerSpanBuilder.startSpan()).thenReturn(spyServerSpan);
    when(tracer.spanBuilderWithRemoteParent(anyString(), any(SpanContext.class)))
        .thenReturn(spyServerSpanBuilder);
    when(mockTracingPropagationHandler.toByteArray(any(SpanContext.class)))
        .thenReturn(binarySpanContext);
    when(mockTracingPropagationHandler.fromByteArray(any(byte[].class)))
        .thenReturn(fakeClientSpanContext);
    censusStats =
        new CensusStatsModule(
            tagger, tagCtxSerializer, statsRecorder, fakeClock.getStopwatchSupplier(), true);
    censusTracing = new CensusTracingModule(tracer, mockTracingPropagationHandler);
  }

  @After
  public void wrapUp() {
    assertNull(statsRecorder.pollRecord());
  }

  @Test
  public void clientInterceptorNoCustomTag() {
    testClientInterceptors(false);
  }

  @Test
  public void clientInterceptorCustomTag() {
    testClientInterceptors(true);
  }

  // Test that Census ClientInterceptors uses the TagContext and Span out of the current Context
  // to create the ClientCallTracer, and that it intercepts ClientCall.Listener.onClose() to call
  // ClientCallTracer.callEnded().
  private void testClientInterceptors(boolean nonDefaultContext) {
    grpcServerRule.getServiceRegistry().addService(
        ServerServiceDefinition.builder("package1.service2").addMethod(
            method, new ServerCallHandler<String, String>() {
                @Override
                public ServerCall.Listener<String> startCall(
                    ServerCall<String, String> call, Metadata headers) {
                  call.sendHeaders(new Metadata());
                  call.sendMessage("Hello");
                  call.close(
                      Status.PERMISSION_DENIED.withDescription("No you don't"), new Metadata());
                  return mockServerCallListener;
                }
              }).build());

    final AtomicReference<CallOptions> capturedCallOptions = new AtomicReference<CallOptions>();
    ClientInterceptor callOptionsCaptureInterceptor = new ClientInterceptor() {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
          capturedCallOptions.set(callOptions);
          return next.newCall(method, callOptions);
        }
      };
    Channel interceptedChannel =
        ClientInterceptors.intercept(
            grpcServerRule.getChannel(), callOptionsCaptureInterceptor,
            censusStats.getClientInterceptor(true), censusTracing.getClientInterceptor());
    ClientCall<String, String> call;
    if (nonDefaultContext) {
      Context ctx =
          Context.ROOT.withValues(
              TAG_CONTEXT_KEY,
              tagger.emptyBuilder().put(
                  StatsTestUtils.EXTRA_TAG, TagValue.create("extra value")).build(),
              ContextUtils.CONTEXT_SPAN_KEY,
              fakeClientParentSpan);
      Context origCtx = ctx.attach();
      try {
        call = interceptedChannel.newCall(method, CALL_OPTIONS);
      } finally {
        ctx.detach(origCtx);
      }
    } else {
      assertEquals(Tags.getTagger().empty(), TAG_CONTEXT_KEY.get());
      assertNull(ContextUtils.CONTEXT_SPAN_KEY.get());
      call = interceptedChannel.newCall(method, CALL_OPTIONS);
    }

    // The interceptor adds tracer factory to CallOptions
    assertEquals("customvalue", capturedCallOptions.get().getOption(CUSTOM_OPTION));
    assertEquals(2, capturedCallOptions.get().getStreamTracerFactories().size());
    assertTrue(
        capturedCallOptions.get().getStreamTracerFactories().get(0)
        instanceof CensusTracingModule.ClientCallTracer);
    assertTrue(
        capturedCallOptions.get().getStreamTracerFactories().get(1)
        instanceof CensusStatsModule.ClientCallTracer);

    // Make the call
    Metadata headers = new Metadata();
    call.start(mockClientCallListener, headers);
    assertNull(statsRecorder.pollRecord());
    if (nonDefaultContext) {
      verify(tracer).spanBuilderWithExplicitParent(
          eq("Sent.package1.service2.method3"), same(fakeClientParentSpan));
      verify(spyClientSpanBuilder).setRecordEvents(eq(true));
    } else {
      verify(tracer).spanBuilderWithExplicitParent(
          eq("Sent.package1.service2.method3"), isNull(Span.class));
      verify(spyClientSpanBuilder).setRecordEvents(eq(true));
    }
    verify(spyClientSpan, never()).end(any(EndSpanOptions.class));

    // End the call
    call.halfClose();
    call.request(1);

    verify(mockClientCallListener).onClose(statusCaptor.capture(), any(Metadata.class));
    Status status = statusCaptor.getValue();
    assertEquals(Status.Code.PERMISSION_DENIED, status.getCode());
    assertEquals("No you don't", status.getDescription());

    // The intercepting listener calls callEnded() on ClientCallTracer, which records to Census.
    StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
    assertNotNull(record);
    TagValue methodTag = record.tags.get(RpcMeasureConstants.RPC_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    TagValue statusTag = record.tags.get(RpcMeasureConstants.RPC_STATUS);
    assertEquals(Status.Code.PERMISSION_DENIED.toString(), statusTag.asString());
    if (nonDefaultContext) {
      TagValue extraTag = record.tags.get(StatsTestUtils.EXTRA_TAG);
      assertEquals("extra value", extraTag.asString());
    } else {
      assertNull(record.tags.get(StatsTestUtils.EXTRA_TAG));
    }
    verify(spyClientSpan).end(
        EndSpanOptions.builder()
            .setStatus(
                io.opencensus.trace.Status.PERMISSION_DENIED
                    .withDescription("No you don't"))
            .setSampleToLocalSpanStore(false)
            .build());
    verify(spyClientSpan, never()).end();
  }

  @Test
  public void clientBasicStatsDefaultContext() {
    CensusStatsModule.ClientCallTracer callTracer =
        censusStats.newClientCallTracer(tagger.empty(), method.getFullMethodName(), true);
    Metadata headers = new Metadata();
    ClientStreamTracer tracer = callTracer.newClientStreamTracer(CallOptions.DEFAULT, headers);

    fakeClock.forwardTime(30, MILLISECONDS);
    tracer.outboundHeaders();

    fakeClock.forwardTime(100, MILLISECONDS);
    tracer.outboundMessage(0);
    tracer.outboundWireSize(1028);
    tracer.outboundUncompressedSize(1128);

    fakeClock.forwardTime(16, MILLISECONDS);
    tracer.inboundMessage(0);
    tracer.inboundWireSize(33);
    tracer.inboundUncompressedSize(67);
    tracer.outboundMessage(1);
    tracer.outboundWireSize(99);
    tracer.outboundUncompressedSize(865);

    fakeClock.forwardTime(24, MILLISECONDS);
    tracer.inboundMessage(1);
    tracer.inboundWireSize(154);
    tracer.inboundUncompressedSize(552);
    tracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);

    StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
    assertNotNull(record);
    assertNoServerContent(record);
    TagValue methodTag = record.tags.get(RpcMeasureConstants.RPC_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    TagValue statusTag = record.tags.get(RpcMeasureConstants.RPC_STATUS);
    assertEquals(Status.Code.OK.toString(), statusTag.asString());
    assertNull(record.getMetric(RpcMeasureConstants.RPC_CLIENT_ERROR_COUNT));
    assertEquals(2, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_REQUEST_COUNT));
    assertEquals(
        1028 + 99, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_REQUEST_BYTES));
    assertEquals(
        1128 + 865,
        record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES));
    assertEquals(2, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_RESPONSE_COUNT));
    assertEquals(
        33 + 154, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_RESPONSE_BYTES));
    assertEquals(67 + 552,
        record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES));
    assertEquals(30 + 100 + 16 + 24,
        record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_ROUNDTRIP_LATENCY));
  }

  @Test
  public void clientBasicTracingDefaultSpan() {
    CensusTracingModule.ClientCallTracer callTracer =
        censusTracing.newClientCallTracer(null, method);
    Metadata headers = new Metadata();
    ClientStreamTracer clientStreamTracer =
        callTracer.newClientStreamTracer(CallOptions.DEFAULT, headers);
    verify(tracer).spanBuilderWithExplicitParent(
        eq("Sent.package1.service2.method3"), isNull(Span.class));
    verify(spyClientSpan, never()).end(any(EndSpanOptions.class));

    clientStreamTracer.outboundMessage(0);
    clientStreamTracer.outboundMessageSent(0, 882, -1);
    clientStreamTracer.inboundMessage(0);
    clientStreamTracer.outboundMessage(1);
    clientStreamTracer.outboundMessageSent(1, -1, 27);
    clientStreamTracer.inboundMessageRead(0, 255, 90);

    clientStreamTracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);

    InOrder inOrder = inOrder(spyClientSpan);
    inOrder.verify(spyClientSpan, times(3)).addNetworkEvent(networkEventCaptor.capture());
    List<NetworkEvent> events = networkEventCaptor.getAllValues();
    assertEquals(
        NetworkEvent.builder(Type.SENT, 0).setCompressedMessageSize(882).build(), events.get(0));
    assertEquals(
        NetworkEvent.builder(Type.SENT, 1).setUncompressedMessageSize(27).build(), events.get(1));
    assertEquals(
        NetworkEvent.builder(Type.RECV, 0)
            .setCompressedMessageSize(255)
            .setUncompressedMessageSize(90)
            .build(),
        events.get(2));
    inOrder.verify(spyClientSpan).end(
        EndSpanOptions.builder()
            .setStatus(io.opencensus.trace.Status.OK)
            .setSampleToLocalSpanStore(false)
            .build());
    verifyNoMoreInteractions(spyClientSpan);
    verifyNoMoreInteractions(tracer);
  }

  @Test
  public void clientTracingSampledToLocalSpanStore() {
    CensusTracingModule.ClientCallTracer callTracer =
        censusTracing.newClientCallTracer(null, sampledMethod);
    Metadata headers = new Metadata();
    callTracer.callEnded(Status.OK);

    verify(spyClientSpan).end(
        EndSpanOptions.builder()
            .setStatus(io.opencensus.trace.Status.OK)
            .setSampleToLocalSpanStore(true)
            .build());
  }

  @Test
  public void clientStreamNeverCreatedStillRecordStats() {
    CensusStatsModule.ClientCallTracer callTracer =
        censusStats.newClientCallTracer(
            tagger.empty(), method.getFullMethodName(), true);

    fakeClock.forwardTime(3000, MILLISECONDS);
    callTracer.callEnded(Status.DEADLINE_EXCEEDED.withDescription("3 seconds"));

    StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
    assertNotNull(record);
    assertNoServerContent(record);
    TagValue methodTag = record.tags.get(RpcMeasureConstants.RPC_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    TagValue statusTag = record.tags.get(RpcMeasureConstants.RPC_STATUS);
    assertEquals(Status.Code.DEADLINE_EXCEEDED.toString(), statusTag.asString());
    assertEquals(1, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_ERROR_COUNT));
    assertEquals(0, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_REQUEST_COUNT));
    assertEquals(0, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_REQUEST_BYTES));
    assertEquals(0,
        record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES));
    assertEquals(0, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_RESPONSE_COUNT));
    assertEquals(0, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_RESPONSE_BYTES));
    assertEquals(0,
        record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES));
    assertEquals(
        3000, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_CLIENT_ROUNDTRIP_LATENCY));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_CLIENT_SERVER_ELAPSED_TIME));
  }

  @Test
  public void clientStreamNeverCreatedStillRecordTracing() {
    CensusTracingModule.ClientCallTracer callTracer =
        censusTracing.newClientCallTracer(fakeClientParentSpan, method);
    verify(tracer).spanBuilderWithExplicitParent(
        eq("Sent.package1.service2.method3"), same(fakeClientParentSpan));
    verify(spyClientSpanBuilder).setRecordEvents(eq(true));

    callTracer.callEnded(Status.DEADLINE_EXCEEDED.withDescription("3 seconds"));
    verify(spyClientSpan).end(
        EndSpanOptions.builder()
            .setStatus(
                io.opencensus.trace.Status.DEADLINE_EXCEEDED
                    .withDescription("3 seconds"))
            .setSampleToLocalSpanStore(false)
            .build());
    verifyNoMoreInteractions(spyClientSpan);
  }

  @Test
  public void statsHeadersPropagateTags_record() {
    subtestStatsHeadersPropagateTags(true, true);
  }

  @Test
  public void statsHeadersPropagateTags_notRecord() {
    subtestStatsHeadersPropagateTags(true, false);
  }

  @Test
  public void statsHeadersNotPropagateTags_record() {
    subtestStatsHeadersPropagateTags(false, true);
  }

  @Test
  public void statsHeadersNotPropagateTags_notRecord() {
    subtestStatsHeadersPropagateTags(false, false);
  }

  private void subtestStatsHeadersPropagateTags(boolean propagate, boolean recordStats) {
    // EXTRA_TAG is propagated by the FakeStatsContextFactory. Note that not all tags are
    // propagated.  The StatsContextFactory decides which tags are to propagated.  gRPC facilitates
    // the propagation by putting them in the headers.
    TagContext clientCtx = tagger.emptyBuilder().put(
        StatsTestUtils.EXTRA_TAG, TagValue.create("extra-tag-value-897")).build();
    CensusStatsModule census =
        new CensusStatsModule(
            tagger,
            tagCtxSerializer,
            statsRecorder,
            fakeClock.getStopwatchSupplier(),
            propagate);
    Metadata headers = new Metadata();
    CensusStatsModule.ClientCallTracer callTracer =
        census.newClientCallTracer(clientCtx, method.getFullMethodName(), recordStats);
    // This propagates clientCtx to headers if propagates==true
    callTracer.newClientStreamTracer(CallOptions.DEFAULT, headers);
    if (propagate) {
      assertTrue(headers.containsKey(census.statsHeader));
    } else {
      assertFalse(headers.containsKey(census.statsHeader));
      return;
    }

    ServerStreamTracer serverTracer =
        census.getServerTracerFactory(recordStats).newServerStreamTracer(
            method.getFullMethodName(), headers);
    // Server tracer deserializes clientCtx from the headers, so that it records stats with the
    // propagated tags.
    Context serverContext = serverTracer.filterContext(Context.ROOT);
    // It also put clientCtx in the Context seen by the call handler
    assertEquals(
        tagger.toBuilder(clientCtx).put(
            RpcMeasureConstants.RPC_METHOD,
            TagValue.create(method.getFullMethodName())).build(),
        TAG_CONTEXT_KEY.get(serverContext));

    // Verifies that the server tracer records the status with the propagated tag
    serverTracer.streamClosed(Status.OK);

    if (recordStats) {
      StatsTestUtils.MetricsRecord serverRecord = statsRecorder.pollRecord();
      assertNotNull(serverRecord);
      assertNoClientContent(serverRecord);
      TagValue serverMethodTag = serverRecord.tags.get(RpcMeasureConstants.RPC_METHOD);
      assertEquals(method.getFullMethodName(), serverMethodTag.asString());
      TagValue serverStatusTag = serverRecord.tags.get(RpcMeasureConstants.RPC_STATUS);
      assertEquals(Status.Code.OK.toString(), serverStatusTag.asString());
      assertNull(serverRecord.getMetric(RpcMeasureConstants.RPC_SERVER_ERROR_COUNT));
      TagValue serverPropagatedTag = serverRecord.tags.get(StatsTestUtils.EXTRA_TAG);
      assertEquals("extra-tag-value-897", serverPropagatedTag.asString());
    }

    // Verifies that the client tracer factory uses clientCtx, which includes the custom tags, to
    // record stats.
    callTracer.callEnded(Status.OK);

    if (recordStats) {
      StatsTestUtils.MetricsRecord clientRecord = statsRecorder.pollRecord();
      assertNotNull(clientRecord);
      assertNoServerContent(clientRecord);
      TagValue clientMethodTag = clientRecord.tags.get(RpcMeasureConstants.RPC_METHOD);
      assertEquals(method.getFullMethodName(), clientMethodTag.asString());
      TagValue clientStatusTag = clientRecord.tags.get(RpcMeasureConstants.RPC_STATUS);
      assertEquals(Status.Code.OK.toString(), clientStatusTag.asString());
      assertNull(clientRecord.getMetric(RpcMeasureConstants.RPC_CLIENT_ERROR_COUNT));
      TagValue clientPropagatedTag = clientRecord.tags.get(StatsTestUtils.EXTRA_TAG);
      assertEquals("extra-tag-value-897", clientPropagatedTag.asString());
    }

    if (!recordStats) {
      assertNull(statsRecorder.pollRecord());
    }
  }

  @Test
  public void statsHeadersNotPropagateDefaultContext() {
    CensusStatsModule.ClientCallTracer callTracer =
        censusStats.newClientCallTracer(tagger.empty(), method.getFullMethodName(), true);
    Metadata headers = new Metadata();
    callTracer.newClientStreamTracer(CallOptions.DEFAULT, headers);
    assertFalse(headers.containsKey(censusStats.statsHeader));
  }

  @Test
  public void statsHeaderMalformed() {
    // Construct a malformed header and make sure parsing it will throw
    byte[] statsHeaderValue = new byte[]{1};
    Metadata.Key<byte[]> arbitraryStatsHeader =
        Metadata.Key.of("grpc-tags-bin", Metadata.BINARY_BYTE_MARSHALLER);
    try {
      tagCtxSerializer.fromByteArray(statsHeaderValue);
      fail("Should have thrown");
    } catch (Exception e) {
      // Expected
    }

    // But the header key will return a default context for it
    Metadata headers = new Metadata();
    assertNull(headers.get(censusStats.statsHeader));
    headers.put(arbitraryStatsHeader, statsHeaderValue);
    assertSame(tagger.empty(), headers.get(censusStats.statsHeader));
  }

  @Test
  public void traceHeadersPropagateSpanContext() throws Exception {
    CensusTracingModule.ClientCallTracer callTracer =
        censusTracing.newClientCallTracer(fakeClientParentSpan, method);
    Metadata headers = new Metadata();
    callTracer.newClientStreamTracer(CallOptions.DEFAULT, headers);

    verify(mockTracingPropagationHandler).toByteArray(same(fakeClientSpanContext));
    verifyNoMoreInteractions(mockTracingPropagationHandler);
    verify(tracer).spanBuilderWithExplicitParent(
        eq("Sent.package1.service2.method3"), same(fakeClientParentSpan));
    verify(spyClientSpanBuilder).setRecordEvents(eq(true));
    verifyNoMoreInteractions(tracer);
    assertTrue(headers.containsKey(censusTracing.tracingHeader));

    ServerStreamTracer serverTracer =
        censusTracing.getServerTracerFactory().newServerStreamTracer(
            method.getFullMethodName(), headers);
    verify(mockTracingPropagationHandler).fromByteArray(same(binarySpanContext));
    verify(tracer).spanBuilderWithRemoteParent(
        eq("Recv.package1.service2.method3"), same(spyClientSpan.getContext()));
    verify(spyServerSpanBuilder).setRecordEvents(eq(true));

    Context filteredContext = serverTracer.filterContext(Context.ROOT);
    assertSame(spyServerSpan, ContextUtils.CONTEXT_SPAN_KEY.get(filteredContext));
  }

  @Test
  public void traceHeaderMalformed() throws Exception {
    // As comparison, normal header parsing
    Metadata headers = new Metadata();
    headers.put(censusTracing.tracingHeader, fakeClientSpanContext);
    // mockTracingPropagationHandler was stubbed to always return fakeServerParentSpanContext
    assertSame(spyClientSpan.getContext(), headers.get(censusTracing.tracingHeader));

    // Make BinaryPropagationHandler always throw when parsing the header
    when(mockTracingPropagationHandler.fromByteArray(any(byte[].class)))
        .thenThrow(new SpanContextParseException("Malformed header"));

    headers = new Metadata();
    assertNull(headers.get(censusTracing.tracingHeader));
    headers.put(censusTracing.tracingHeader, fakeClientSpanContext);
    assertSame(SpanContext.INVALID, headers.get(censusTracing.tracingHeader));
    assertNotSame(spyClientSpan.getContext(), SpanContext.INVALID);

    // A null Span is used as the parent in this case
    censusTracing.getServerTracerFactory().newServerStreamTracer(
        method.getFullMethodName(), headers);
    verify(tracer).spanBuilderWithRemoteParent(
        eq("Recv.package1.service2.method3"), isNull(SpanContext.class));
    verify(spyServerSpanBuilder).setRecordEvents(eq(true));
  }

  @Test
  public void serverBasicStatsNoHeaders() {
    ServerStreamTracer.Factory tracerFactory = censusStats.getServerTracerFactory(true);
    ServerStreamTracer tracer =
        tracerFactory.newServerStreamTracer(method.getFullMethodName(), new Metadata());

    Context filteredContext = tracer.filterContext(Context.ROOT);
    TagContext statsCtx = TAG_CONTEXT_KEY.get(filteredContext);
    assertEquals(
        tagger
            .emptyBuilder()
            .put(
                RpcMeasureConstants.RPC_METHOD,
                TagValue.create(method.getFullMethodName()))
            .build(),
        statsCtx);

    tracer.inboundMessage(0);
    tracer.inboundWireSize(34);
    tracer.inboundUncompressedSize(67);

    fakeClock.forwardTime(100, MILLISECONDS);
    tracer.outboundMessage(0);
    tracer.outboundWireSize(1028);
    tracer.outboundUncompressedSize(1128);

    fakeClock.forwardTime(16, MILLISECONDS);
    tracer.inboundMessage(1);
    tracer.inboundWireSize(154);
    tracer.inboundUncompressedSize(552);
    tracer.outboundMessage(1);
    tracer.outboundWireSize(99);
    tracer.outboundUncompressedSize(865);

    fakeClock.forwardTime(24, MILLISECONDS);

    tracer.streamClosed(Status.CANCELLED);

    StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
    assertNotNull(record);
    assertNoClientContent(record);
    TagValue methodTag = record.tags.get(RpcMeasureConstants.RPC_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    TagValue statusTag = record.tags.get(RpcMeasureConstants.RPC_STATUS);
    assertEquals(Status.Code.CANCELLED.toString(), statusTag.asString());
    assertEquals(1, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_SERVER_ERROR_COUNT));
    assertEquals(2, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_SERVER_RESPONSE_COUNT));
    assertEquals(
        1028 + 99, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_SERVER_RESPONSE_BYTES));
    assertEquals(
        1128 + 865,
        record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_SERVER_UNCOMPRESSED_RESPONSE_BYTES));
    assertEquals(2, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_SERVER_REQUEST_COUNT));
    assertEquals(
        34 + 154, record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_SERVER_REQUEST_BYTES));
    assertEquals(67 + 552,
        record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_SERVER_UNCOMPRESSED_REQUEST_BYTES));
    assertEquals(100 + 16 + 24,
        record.getMetricAsLongOrFail(RpcMeasureConstants.RPC_SERVER_SERVER_LATENCY));
  }

  @Test
  public void serverBasicTracingNoHeaders() {
    ServerStreamTracer.Factory tracerFactory = censusTracing.getServerTracerFactory();
    ServerStreamTracer serverStreamTracer =
        tracerFactory.newServerStreamTracer(method.getFullMethodName(), new Metadata());
    verifyZeroInteractions(mockTracingPropagationHandler);
    verify(tracer).spanBuilderWithRemoteParent(
        eq("Recv.package1.service2.method3"), isNull(SpanContext.class));
    verify(spyServerSpanBuilder).setRecordEvents(eq(true));

    Context filteredContext = serverStreamTracer.filterContext(Context.ROOT);
    assertSame(spyServerSpan, ContextUtils.CONTEXT_SPAN_KEY.get(filteredContext));

    serverStreamTracer.serverCallStarted(new FakeServerCall<String, String>(method));

    verify(spyServerSpan, never()).end(any(EndSpanOptions.class));

    serverStreamTracer.outboundMessage(0);
    serverStreamTracer.outboundMessageSent(0, 882, -1);
    serverStreamTracer.inboundMessage(0);
    serverStreamTracer.outboundMessage(1);
    serverStreamTracer.outboundMessageSent(1, -1, 27);
    serverStreamTracer.inboundMessageRead(0, 255, 90);

    serverStreamTracer.streamClosed(Status.CANCELLED);

    InOrder inOrder = inOrder(spyServerSpan);
    inOrder.verify(spyServerSpan, times(3)).addNetworkEvent(networkEventCaptor.capture());
    List<NetworkEvent> events = networkEventCaptor.getAllValues();
    assertEquals(
        NetworkEvent.builder(Type.SENT, 0).setCompressedMessageSize(882).build(), events.get(0));
    assertEquals(
        NetworkEvent.builder(Type.SENT, 1).setUncompressedMessageSize(27).build(), events.get(1));
    assertEquals(
        NetworkEvent.builder(Type.RECV, 0)
            .setCompressedMessageSize(255)
            .setUncompressedMessageSize(90)
            .build(),
        events.get(2));
    inOrder.verify(spyServerSpan).end(
        EndSpanOptions.builder()
            .setStatus(io.opencensus.trace.Status.CANCELLED)
            .setSampleToLocalSpanStore(false)
            .build());
    verifyNoMoreInteractions(spyServerSpan);
  }

  @Test
  public void serverTracingSampledToLocalSpanStore() {
    ServerStreamTracer.Factory tracerFactory = censusTracing.getServerTracerFactory();
    ServerStreamTracer serverStreamTracer =
        tracerFactory.newServerStreamTracer(sampledMethod.getFullMethodName(), new Metadata());

    serverStreamTracer.filterContext(Context.ROOT);
    serverStreamTracer.serverCallStarted(new FakeServerCall<String, String>(sampledMethod));
    serverStreamTracer.streamClosed(Status.CANCELLED);

    verify(spyServerSpan).end(
        EndSpanOptions.builder()
            .setStatus(io.opencensus.trace.Status.CANCELLED)
            .setSampleToLocalSpanStore(true)
            .build());
  }

  @Test
  public void serverTracingNotSampledToLocalSpanStore_whenServerCallNotCreated() {
    ServerStreamTracer.Factory tracerFactory = censusTracing.getServerTracerFactory();
    ServerStreamTracer serverStreamTracer =
        tracerFactory.newServerStreamTracer(sampledMethod.getFullMethodName(), new Metadata());

    serverStreamTracer.streamClosed(Status.CANCELLED);

    verify(spyServerSpan).end(
        EndSpanOptions.builder()
            .setStatus(io.opencensus.trace.Status.CANCELLED)
            .setSampleToLocalSpanStore(false)
            .build());
  }

  @Test
  public void convertToTracingStatus() {
    // Without description
    for (Status.Code grpcCode : Status.Code.values()) {
      Status grpcStatus = Status.fromCode(grpcCode);
      io.opencensus.trace.Status tracingStatus =
          CensusTracingModule.convertStatus(grpcStatus);
      assertEquals(grpcCode.toString(), tracingStatus.getCanonicalCode().toString());
      assertNull(tracingStatus.getDescription());
    }

    // With description
    for (Status.Code grpcCode : Status.Code.values()) {
      Status grpcStatus = Status.fromCode(grpcCode).withDescription("This is my description");
      io.opencensus.trace.Status tracingStatus =
          CensusTracingModule.convertStatus(grpcStatus);
      assertEquals(grpcCode.toString(), tracingStatus.getCanonicalCode().toString());
      assertEquals(grpcStatus.getDescription(), tracingStatus.getDescription());
    }
  }


  @Test
  public void generateTraceSpanName() {
    assertEquals(
        "Sent.io.grpc.Foo", CensusTracingModule.generateTraceSpanName(false, "io.grpc/Foo"));
    assertEquals(
        "Recv.io.grpc.Bar", CensusTracingModule.generateTraceSpanName(true, "io.grpc/Bar"));
  }

  private static void assertNoServerContent(StatsTestUtils.MetricsRecord record) {
    assertNull(record.getMetric(RpcMeasureConstants.RPC_SERVER_ERROR_COUNT));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_SERVER_REQUEST_COUNT));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_SERVER_RESPONSE_COUNT));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_SERVER_REQUEST_BYTES));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_SERVER_RESPONSE_BYTES));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_SERVER_SERVER_ELAPSED_TIME));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_SERVER_SERVER_LATENCY));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_SERVER_UNCOMPRESSED_REQUEST_BYTES));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_SERVER_UNCOMPRESSED_RESPONSE_BYTES));
  }

  private static void assertNoClientContent(StatsTestUtils.MetricsRecord record) {
    assertNull(record.getMetric(RpcMeasureConstants.RPC_CLIENT_ERROR_COUNT));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_CLIENT_REQUEST_COUNT));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_CLIENT_RESPONSE_COUNT));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_CLIENT_REQUEST_BYTES));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_CLIENT_RESPONSE_BYTES));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_CLIENT_ROUNDTRIP_LATENCY));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_CLIENT_SERVER_ELAPSED_TIME));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES));
    assertNull(record.getMetric(RpcMeasureConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES));
  }

  private static class FakeServerCall<ReqT, RespT> extends ServerCall<ReqT, RespT> {
    final MethodDescriptor<ReqT, RespT> method;

    FakeServerCall(MethodDescriptor<ReqT, RespT> method) {
      this.method = method;
    }

    @Override
    public void request(int numMessages) {
      throw new AssertionError("Should not be called");
    }

    @Override
    public void sendHeaders(Metadata headers) {
      throw new AssertionError("Should not be called");
    }

    @Override
    public void sendMessage(RespT message) {
      throw new AssertionError("Should not be called");
    }

    @Override
    public void close(Status status, Metadata trailers) {
      throw new AssertionError("Should not be called");
    }

    @Override
    public boolean isCancelled() {
      throw new AssertionError("Should not be called");
    }

    @Override
    public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
      return method;
    }
  }
}
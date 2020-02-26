/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2019 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2019 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp;

import static org.opennms.netmgt.telemetry.protocols.bmp.adapter.BmpAdapterTools.address;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.opennms.core.utils.InetAddressUtils;
import org.opennms.netmgt.telemetry.api.adapter.TelemetryMessageLog;
import org.opennms.netmgt.telemetry.api.adapter.TelemetryMessageLogEntry;
import org.opennms.netmgt.telemetry.config.api.AdapterDefinition;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.BmpAdapterTools;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.Message;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.Record;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.Type;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.BaseAttribute;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.Collector;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.Peer;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.Router;
import org.opennms.netmgt.telemetry.protocols.bmp.adapter.openbmp.proto.records.Stat;
import org.opennms.netmgt.telemetry.protocols.bmp.transport.Transport;
import org.opennms.netmgt.telemetry.protocols.collection.AbstractAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.InvalidProtocolBufferException;

public class BmpIntegrationAdapter extends AbstractAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(BmpIntegrationAdapter.class);

    private final AtomicLong sequence = new AtomicLong();

    private final BmpMessageHandler handler;

    public BmpIntegrationAdapter(final AdapterDefinition adapterConfig,
                                 final MetricRegistry metricRegistry) {
        super(adapterConfig, metricRegistry);
        this.handler = new BmpKafkaProducer(adapterConfig);
    }

    public BmpIntegrationAdapter(final AdapterDefinition adapterConfig,
                                 final MetricRegistry metricRegistry,
                                 final BmpMessageHandler handler) {
        super(adapterConfig, metricRegistry);
        this.handler = Objects.requireNonNull(handler);
    }

    private Optional<Message> handleHeartbeatMessage(final Transport.Message message,
                                                     final Transport.Heartbeat heartbeat,
                                                     final Context context) {
        final Collector collector = new Collector();

        switch (heartbeat.getMode()) {
            case STARTED:
                collector.action = Collector.Action.STARTED;
                break;
            case STOPPED:
                collector.action = Collector.Action.STOPPED;
                break;
            case PERIODIC:
                collector.action = Collector.Action.HEARTBEAT;
                break;
            case CHANGED:
                collector.action = Collector.Action.CHANGE;
                break;
        }

        collector.sequence = sequence.getAndIncrement();
        collector.adminId = context.adminId;
        collector.hash = context.collectorHashId;
        collector.routers = Lists.transform(heartbeat.getRoutersList(), BmpAdapterTools::address);
        collector.timestamp = context.timestamp;

        return Optional.of(new Message(context.collectorHashId, Type.COLLECTOR, ImmutableList.of(collector)));
    }

    private void handleInitiationMessage(final Transport.Message message,
                                                      final Transport.InitiationPacket initiation,
                                                      final Context context) {
        final Router router = new Router();
        router.action = Router.Action.INIT;
        router.sequence = sequence.getAndIncrement();
        router.name = initiation.getSysName();
        router.hash = context.routerHashId;
        router.ipAddress = context.sourceAddress;
        router.description = initiation.getSysDesc();
        router.termCode = null;
        router.termReason = null;
        router.initData = initiation.getMessage();
        router.termData = null;
        router.timestamp = context.timestamp;
        router.bgpId = BmpAdapterTools.address(initiation.getBgpId());

        handler.handle(new Message(context.collectorHashId, Type.ROUTER, ImmutableList.of(router)));
    }

    private void handleTerminationMessage(final Transport.Message message,
                                                       final Transport.TerminationPacket termination,
                                                       final Context context) {
        final Router router = new Router();
        router.action = Router.Action.TERM;
        router.sequence = sequence.getAndIncrement();
        router.name = null;
        router.hash = context.routerHashId;
        router.ipAddress = context.sourceAddress;
        router.description = null;
        router.termCode = termination.getReason();

        switch (router.termCode) {
            case 0:
                router.termReason = "Session administratively closed.  The session might be re-initiated";
                break;
            case 1:
                router.termReason = "Unspecified reason";
                break;
            case 2:
                router.termReason = "Out of resources.  The router has exhausted resources available for the BMP session";
                break;
            case 3:
                router.termReason = "Redundant connection.  The router has determined that this connection is redundant with another one";
                break;
            case 4:
                router.termReason = "Session permanently administratively closed, will not be re-initiated";
                break;
            default:
                router.termReason = "Unknown reason";
        }

        router.initData = null;
        router.termData = termination.getMessage();
        router.timestamp = context.timestamp;
        router.bgpId = null;

        handler.handle(new Message(context.collectorHashId, Type.ROUTER, ImmutableList.of(router)));
    }

    private void handlePeerUpNotification(final Transport.Message message,
                                                       final Transport.PeerUpPacket peerUp,
                                                       final Context context) {
        final Transport.Peer bgpPeer = peerUp.getPeer();
        final Peer peer = new Peer();
        peer.action = Peer.Action.UP;
        peer.sequence = sequence.getAndIncrement();
        peer.name = peerUp.getSysName();
        peer.hash = Record.hash(bgpPeer.getAddress(),
                                bgpPeer.getDistinguisher(),
                                context.routerHashId);
        peer.routerHash = context.routerHashId;
        peer.remoteBgpId = InetAddressUtils.str(address(peerUp.getRecvMsg().getId())); // FIXME; This is weird
        peer.routerIp = context.sourceAddress;
        peer.timestamp = context.timestamp;
        peer.remoteAsn = (long) peerUp.getRecvMsg().getAs();
        peer.remoteIp = address(bgpPeer.getAddress());
        peer.peerRd = Long.toString(bgpPeer.getDistinguisher());
        peer.remotePort = peerUp.getRemotePort();
        peer.localAsn = (long) peerUp.getSendMsg().getAs(); // FIXME: long vs int?
        peer.localIp = address(peerUp.getLocalAddress());
        peer.localPort = peerUp.getLocalPort();
        peer.localBgpId = InetAddressUtils.str(address(peerUp.getSendMsg().getId())); // FIXME; still weird
        peer.infoData = peerUp.getMessage();
        peer.advertisedCapabilities = ""; // TODO: Not parsed right now
        peer.receivedCapabilities = ""; // TODO: Not parsed right now
        peer.remoteHolddown = (long) peerUp.getRecvMsg().getHoldTime(); // FIXME: long vs int?
        peer.advertisedHolddown = (long) peerUp.getRecvMsg().getHoldTime();
        peer.bmpReason = null;
        peer.bgpErrorCode = null;
        peer.bgpErrorSubcode = null;
        peer.errorText = null;
        peer.l3vpn = false; // TODO: Extract from document
        peer.prePolicy = false; // TODO?
        peer.ipv4 = Transport.IpAddress.AddressCase.V4.equals(bgpPeer.getAddress().getAddressCase());
        peer.locRib = false; // TODO: Not implemented (see RFC draft-ietf-grow-bmp-loc-rib)
        peer.locRibFiltered = false; // TODO: Not implemented (see RFC draft-ietf-grow-bmp-loc-rib)
        peer.tableName = ""; // TODO: Not implemented (see RFC draft-ietf-grow-bmp-loc-rib)

        handler.handle(new Message(context.collectorHashId, Type.PEER, ImmutableList.of(peer)));
    }

    private void handlePeerDownNotification(final Transport.Message message,
                                                         final Transport.PeerDownPacket peerDown,
                                                         final Context context) {
        final Transport.Peer bgpPeer = peerDown.getPeer();
        final Peer peer = new Peer();
        peer.action = Peer.Action.DOWN;
        peer.sequence = sequence.getAndIncrement();
        peer.name = null; // FIXME: Can populate?
        peer.hash = Record.hash(bgpPeer.getAddress(),
                bgpPeer.getDistinguisher(),
                context.routerHashId);
        peer.routerHash = context.routerHashId;
        peer.remoteBgpId = null; // FIXME: Can populate?
        peer.routerIp = context.sourceAddress;
        peer.timestamp = context.timestamp;
        peer.remoteAsn = 0L; // FIXME: Can populate?
        peer.remoteIp = address(bgpPeer.getAddress());
        peer.peerRd = Long.toString(bgpPeer.getDistinguisher());
        peer.remotePort = null;
        peer.localAsn = null;
        peer.localIp = null;
        peer.localPort = null;
        peer.localBgpId = null;
        peer.infoData = null;
        peer.advertisedCapabilities = null;
        peer.receivedCapabilities = null;
        peer.remoteHolddown = null;
        peer.advertisedHolddown = null;
        peer.bmpReason = null; // TODO: Extract from document
        peer.bgpErrorCode = null; // TODO: Extract from document
        peer.bgpErrorSubcode = null; // TODO: Extract from document
        peer.errorText = null; // TODO: Extract from document
        peer.l3vpn = false; // TODO: Extract from document
        peer.prePolicy = false; // TODO?
        peer.ipv4 = Transport.IpAddress.AddressCase.V4.equals(bgpPeer.getAddress().getAddressCase());
        peer.locRib = false; // TODO: Not implemented (see RFC draft-ietf-grow-bmp-loc-rib)
        peer.locRibFiltered = false; // TODO: Not implemented (see RFC draft-ietf-grow-bmp-loc-rib)
        peer.tableName = ""; // TODO: Not implemented (see RFC draft-ietf-grow-bmp-loc-rib)

        handler.handle(new Message(context.collectorHashId, Type.PEER, ImmutableList.of(peer)));
    }

    private void handleStatisticReport(final Transport.Message message,
                                                    final Transport.StatisticsReportPacket statisticsReport,
                                                    final Context context) {
        final Transport.Peer peer = statisticsReport.getPeer();
        final Stat stat = new Stat();
        stat.action = Stat.Action.ADD;
        stat.sequence = sequence.getAndIncrement();
        stat.routerHash = Record.hash(context.sourceAddress.getHostAddress(), Integer.toString(context.sourcePort), context.collectorHashId);
        stat.routerIp = context.sourceAddress;
        stat.peerHash = Record.hash(peer.getAddress(), peer.getDistinguisher(), stat.routerHash);
        stat.peerIp = address(peer.getAddress());
        stat.peerAsn = (long)peer.getAs();
        stat.timestamp = context.timestamp;
        stat.prefixesRejected = statisticsReport.getRejected().getCount();
        stat.knownDupPrefixes = statisticsReport.getDuplicatePrefix().getCount();
        stat.knownDupWithdraws = statisticsReport.getDuplicateWithdraw().getCount();
        stat.invalidClusterList = statisticsReport.getInvalidUpdateDueToClusterListLoop().getCount();
        stat.invalidAsPath = statisticsReport.getInvalidUpdateDueToAsPathLoop().getCount();
        stat.invalidOriginatorId = statisticsReport.getInvalidUpdateDueToOriginatorId().getCount();
        stat.invalidAsConfed = statisticsReport.getInvalidUpdateDueToAsConfedLoop().getCount();
        stat.prefixesPrePolicy = statisticsReport.getAdjRibIn().getValue();
        stat.prefixesPostPolicy = statisticsReport.getLocRib().getValue();
        handler.handle(new Message(context.collectorHashId, Type.BMP_STAT, ImmutableList.of(stat)));
    }

    private void handleRouteMonitoringMessage(Transport.Message message, Transport.RouteMonitoringPacket routeMonitoring, Context context) {
        final BaseAttribute baseAttr = new BaseAttribute();
        baseAttr.action = BaseAttribute.Action.ADD;
        baseAttr.sequence = sequence.getAndIncrement();
        baseAttr.routerHash = Record.hash(context.sourceAddress.getHostAddress(), Integer.toString(context.sourcePort), context.collectorHashId);
        // See UpdateMsg::parseAttr_AsPath
        baseAttr.asPathCount = 0;
        StringBuilder asPath = new StringBuilder();
        routeMonitoring.getAttributesList().stream()
                .filter(Transport.RouteMonitoringPacket.PathAttribute::hasAsPath)
                .findFirst()
                .ifPresent(asPathAttr -> {
                    asPathAttr.getAsPath().getSegmentsList().forEach(segment -> {
                        if (Transport.RouteMonitoringPacket.PathAttribute.AsPath.Segment.Type.AS_SET.equals(segment.getType())) {
                            asPath.append("{");
                        }
                        segment.getPathsList().forEach(segmentPath -> {
                            asPath.append(segmentPath);
                            asPath.append(" ");
                            baseAttr.asPathCount++;
                        });
                        if (Transport.RouteMonitoringPacket.PathAttribute.AsPath.Segment.Type.AS_SET.equals(segment.getType())) {
                            asPath.append("}");
                        }
                    });
                });
        baseAttr.asPath = asPath.toString();

        // TODO: Finish base_attribute message and also generate unicast_prefix messages

        handler.handle(new Message(context.collectorHashId, Type.BASE_ATTRIBUTE, ImmutableList.of(baseAttr)));
    }

    @Override
    public void handleMessage(final TelemetryMessageLogEntry messageLogEntry,
                              final TelemetryMessageLog messageLog) {
        LOG.trace("Parsing packet: {}", messageLogEntry);
        final Transport.Message message;
        try {
            message = Transport.Message.parseFrom(messageLogEntry.getByteArray());
        } catch (final InvalidProtocolBufferException e) {
            LOG.error("Invalid message", e);
            return;
        }

        final String collectorHashId = Record.hash(messageLog.getSystemId());
        final String routerHashId = Record.hash(messageLog.getSourceAddress(), Integer.toString(messageLog.getSourcePort()), collectorHashId);
        final Context context = new Context(messageLog.getSystemId(),
                                            collectorHashId,
                                            routerHashId,
                                            Instant.ofEpochMilli(messageLogEntry.getTimestamp()),
                                            InetAddressUtils.addr(messageLog.getSourceAddress()),
                                            messageLog.getSourcePort());

        switch(message.getPacketCase()) {
            case HEARTBEAT:
                handleHeartbeatMessage(message, message.getHeartbeat(), context);
                break;
            case INITIATION:
                handleInitiationMessage(message, message.getInitiation(), context);
                break;
            case TERMINATION:
                handleTerminationMessage(message, message.getTermination(), context);
                break;
            case PEER_UP:
                handlePeerUpNotification(message, message.getPeerUp(), context);
                break;
            case PEER_DOWN:
                handlePeerDownNotification(message, message.getPeerDown(), context);
                break;
            case STATISTICS_REPORT:
                handleStatisticReport(message, message.getStatisticsReport(), context);
                break;
            case ROUTE_MONITORING:
                handleRouteMonitoringMessage(message, message.getRouteMonitoring(), context);
                break;
            case PACKET_NOT_SET:
                break;
        }
    }

    @Override
    public void destroy() {
        this.handler.close();
        super.destroy();
    }

    private static class Context {
        public final String adminId;

        public final String collectorHashId;
        public final String routerHashId;

        public final Instant timestamp;

        public final InetAddress sourceAddress;
        public final int sourcePort;

        private Context(final String adminId,
                        final String collectorHashId,
                        final String routerHashId,
                        final Instant timestamp,
                        final InetAddress sourceAddress,
                        final int sourcePort) {
            this.adminId = Objects.requireNonNull(adminId);
            this.collectorHashId = Objects.requireNonNull(collectorHashId);
            this.routerHashId = Objects.requireNonNull(routerHashId);
            this.timestamp = Objects.requireNonNull(timestamp);
            this.sourceAddress = Objects.requireNonNull(sourceAddress);
            this.sourcePort = sourcePort;
        }
    }
}
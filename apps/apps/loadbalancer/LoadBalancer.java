package edu.wisc.cs.sdn.apps.loadbalancer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.l3routing.IL3Routing;
import edu.wisc.cs.sdn.apps.util.ArpServer;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.internal.DeviceManagerImpl;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.util.MACAddress;

public class LoadBalancer implements IFloodlightModule, IOFSwitchListener,
		IOFMessageListener
{
	public static final String MODULE_NAME = LoadBalancer.class.getSimpleName();
	
	private static final byte TCP_FLAG_SYN = 0x02;
	
	private static final short IDLE_TIMEOUT = 20;
	
	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;
    
    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Interface to L3Routing application
    private IL3Routing l3RoutingApp;
    
    // Switch table in which rules should be installed
    private byte table;
    
    // Set of virtual IPs and the load balancer instances they correspond with
    private Map<Integer,LoadBalancerInstance> instances;

    /**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		
		// Obtain table number from config
		Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));
        
        // Create instances from config
        this.instances = new HashMap<Integer,LoadBalancerInstance>();
        String[] instanceConfigs = config.get("instances").split(";");
        for (String instanceConfig : instanceConfigs)
        {
        	String[] configItems = instanceConfig.split(" ");
        	if (configItems.length != 3)
        	{ 
        		log.error("Ignoring bad instance config: " + instanceConfig);
        		continue;
        	}
        	LoadBalancerInstance instance = new LoadBalancerInstance(
        			configItems[0], configItems[1], configItems[2].split(","));
            this.instances.put(instance.getVirtualIP(), instance);
            log.info("Added load balancer instance: " + instance);
        }
        
		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);
        this.l3RoutingApp = context.getServiceImpl(IL3Routing.class);
        
        /*********************************************************************/
        /* TODO: Initialize other class variables, if necessary              */
        
        /*********************************************************************/
	}

	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.floodlightProv.addOFMessageListener(OFType.PACKET_IN, this);
		
		/*********************************************************************/
		/* TODO: Perform other tasks, if necessary                           */
		
		/*********************************************************************/
	}
	
	/**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override
	public void switchAdded(long switchId) 
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));
		
		/*********************************************************************/
		/* TODO: Install rules to send:                                      */
		/*       (1) packets from new connections to each virtual load       */
		/*       balancer IP to the controller                               */
		/*       (2) ARP packets to the controller, and                      */
		/*       (3) all other packets to the next rule table in the switch  */
		
		/*********************************************************************/
		
		for(Integer virtualIP : instances.keySet()) {
			OFInstructionApplyActions newInst = new OFInstructionApplyActions();
			newInst.setActions(Arrays.asList((OFAction) new OFActionOutput().setPort(OFPort.OFPP_CONTROLLER.getValue())));
			List<OFInstruction> instList = Arrays.asList((OFInstruction) newInst);

			OFMatch arpMatch = new OFMatch();
			arpMatch.setDataLayerType(OFMatch.ETH_TYPE_ARP);
			arpMatch.setField(OFOXMFieldType.ARP_TPA, virtualIP);
			
			SwitchCommands.removeRules(sw, table, arpMatch);
			SwitchCommands.installRule(sw, table, (short) (SwitchCommands.DEFAULT_PRIORITY + 1), arpMatch, instList);

			OFMatch tcpMatch = new OFMatch();
			tcpMatch.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
			tcpMatch.setNetworkDestination(virtualIP);
			
			SwitchCommands.removeRules(sw, table, tcpMatch);
			SwitchCommands.installRule(sw, table, (short) (SwitchCommands.DEFAULT_PRIORITY + 1), tcpMatch, instList);
		}

		OFInstructionGotoTable changeTableInst = new OFInstructionGotoTable(ShortestPathSwitching.table);
		SwitchCommands.installRule(sw, table, SwitchCommands.DEFAULT_PRIORITY, new OFMatch(), Arrays.asList((OFInstruction) changeTableInst));
	
	}
	
	/**
	 * Handle incoming packets sent from switches.
	 * @param sw switch on which the packet was received
	 * @param msg message from the switch
	 * @param cntx the Floodlight context in which the message should be handled
	 * @return indication whether another module should also process the packet
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) 
	{
		// We're only interested in packet-in messages
		if (msg.getType() != OFType.PACKET_IN)
		{ return Command.CONTINUE; }
		OFPacketIn pktIn = (OFPacketIn)msg;
		
		// Handle the packet
		Ethernet ethPkt = new Ethernet();
		ethPkt.deserialize(pktIn.getPacketData(), 0,
				pktIn.getPacketData().length);
		
		/*********************************************************************/
		/* TODO: Send an ARP reply for ARP requests for virtual IPs; for TCP */
		/*       SYNs sent to a virtual IP, select a host and install        */
		/*       connection-specific rules to rewrite IP and MAC addresses;  */
		/*       for all other TCP packets sent to a virtual IP, send a TCP  */
		/*       reset; ignore all other packets                             */
		
		/*********************************************************************/
		short ethernetType = ethPkt.getEtherType();
		if (ethernetType == Ethernet.TYPE_ARP) {
			ARP arpPkt = (ARP) ethPkt.getPayload();

			if (arpPkt.getOpCode() != ARP.OP_REQUEST || arpPkt.getProtocolType() != ARP.PROTO_TYPE_IP) {
				return Command.CONTINUE;
			}

			int vIP = IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress());
			LoadBalancerInstance loadBalancer = this.instances.get(vIP);

			if (isLog)
				log.info(String.format("Received ARP request for virtual IP %s from %s",
						IPv4.fromIPv4Address(vIP),
						MACAddress.valueOf(arpPkt.getSenderHardwareAddress())));

			if (loadBalancer == null) {
				return Command.CONTINUE;
			}

			ARP replyARP = new ARP()
					.setHardwareType(ARP.HW_TYPE_ETHERNET)
					.setProtocolType(ARP.PROTO_TYPE_IP)
					.setHardwareAddressLength(arpPkt.getHardwareAddressLength())
					.setProtocolAddressLength(arpPkt.getProtocolAddressLength())
					.setOpCode(ARP.OP_REPLY)
					.setSenderProtocolAddress(vIP)
					.setSenderHardwareAddress(loadBalancer.getVirtualMAC())
					.setTargetHardwareAddress(arpPkt.getSenderHardwareAddress())
					.setTargetProtocolAddress(arpPkt.getSenderProtocolAddress());

			Ethernet replyEther = (Ethernet) new Ethernet()
					.setEtherType(Ethernet.TYPE_ARP)
					.setDestinationMACAddress(ethPkt.getSourceMACAddress())
					.setSourceMACAddress(loadBalancer.getVirtualMAC())
					.setPayload(replyARP);

			if (isLog)
				log.info(String.format("Sending ARP reply %s -> %s",
						IPv4.fromIPv4Address(vIP),
						MACAddress.valueOf(loadBalancer.getVirtualMAC())));

			SwitchCommands.sendPacket(sw, (short) pktIn.getInPort(), replyEther);

		} else if (ethernetType == Ethernet.TYPE_IPv4) {
			IPv4 ipPkt = (IPv4) ethPkt.getPayload();
			if (ipPkt.getProtocol() != IPv4.PROTOCOL_TCP) {
				return Command.CONTINUE;
			}

			TCP tcpPkt = (TCP) ipPkt.getPayload();
			if (tcpPkt.getFlags() == TCP_FLAG_SYN) {
				if (isLog)
					log.info("TCP_FLAG_SYN Rule");

				LoadBalancerInstance loadBalancer = instances.get(ipPkt.getDestinationAddress());
				int hostIP = loadBalancer.getNextHostIP();
				byte[] hostMAC = this.getHostMACAddress(hostIP);

				OFMatch csMatch = new OFMatch()
						.setDataLayerType(OFMatch.ETH_TYPE_IPV4)
						.setNetworkProtocol(OFMatch.IP_PROTO_TCP)
						.setNetworkSource(ipPkt.getSourceAddress())
						.setNetworkDestination(ipPkt.getDestinationAddress())
						.setTransportSource(tcpPkt.getSourcePort())
						.setTransportDestination(tcpPkt.getDestinationPort());

				OFInstruction defaultInstruction = new OFInstructionGotoTable(ShortestPathSwitching.table);

				List<OFAction> csActions = new ArrayList<OFAction>();
				csActions.add(new OFActionSetField(
						OFOXMFieldType.ETH_DST,
						hostMAC
				));
				csActions.add(new OFActionSetField(
						OFOXMFieldType.IPV4_DST,
						hostIP
				));

				OFInstruction csInstruction = new OFInstructionApplyActions(csActions);

				SwitchCommands.installRule(
						sw,
						table,
						(short) (SwitchCommands.DEFAULT_PRIORITY + 2),
						csMatch,
						Arrays.asList(csInstruction, defaultInstruction),
						SwitchCommands.NO_TIMEOUT,
						IDLE_TIMEOUT
				);

				OFMatch scMatch = new OFMatch()
						.setDataLayerType(OFMatch.ETH_TYPE_IPV4)
						.setNetworkProtocol(OFMatch.IP_PROTO_TCP)
						.setNetworkSource(hostIP)
						.setNetworkDestination(ipPkt.getSourceAddress())
						.setTransportSource(tcpPkt.getDestinationPort())
						.setTransportDestination(tcpPkt.getSourcePort());

				List<OFAction> scActions = new ArrayList<OFAction>();
				scActions.add(new OFActionSetField(
						OFOXMFieldType.IPV4_SRC,
						ipPkt.getDestinationAddress()
				));
				scActions.add(new OFActionSetField(
						OFOXMFieldType.ETH_SRC,
						instances.get(ipPkt.getDestinationAddress()).getVirtualMAC()
				));

				OFInstruction scInstruction = new OFInstructionApplyActions(scActions);

				SwitchCommands.installRule(
						sw,
						table,
						(short) (SwitchCommands.DEFAULT_PRIORITY + 2),
						scMatch,
						Arrays.asList(scInstruction, defaultInstruction),
						SwitchCommands.NO_TIMEOUT,
						IDLE_TIMEOUT
				);
			} else {
				if (isLog)
					log.info("Other TCP Rule");

				ipPkt.setFlags(TCP_FLAG_RST);
				ipPkt.setDestinationAddress(ipPkt.getSourceAddress());
				ipPkt.setSourceAddress(ipPkt.getDestinationAddress());
				ethPkt.setDestinationMACAddress(ethPkt.getSourceMACAddress());
				ethPkt.setSourceMACAddress(ethPkt.getSourceMACAddress());
				SwitchCommands.sendPacket(sw, (short) pktIn.getInPort(), ethPkt);
			}
		}



		return Command.CONTINUE;
	}
	
	/**
	 * Returns the MAC address for a host, given the host's IP address.
	 * @param hostIPAddress the host's IP address
	 * @return the hosts's MAC address, null if unknown
	 */
	private byte[] getHostMACAddress(int hostIPAddress)
	{
		Iterator<? extends IDevice> iterator = this.deviceProv.queryDevices(
				null, null, hostIPAddress, null, null);
		if (!iterator.hasNext())
		{ return null; }
		IDevice device = iterator.next();
		return MACAddress.valueOf(device.getMACAddress()).toBytes();
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) 
	{ /* Nothing we need to do, since the switch is no longer active */ }

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId)
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) 
	{ /* Nothing we need to do, since load balancer rules are port-agnostic */}

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) 
	{ /* Nothing we need to do */ }
	
    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() 
	{ return null; }

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			getServiceImpls() 
	{ return null; }

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> 
			getModuleDependencies() 
	{
		Collection<Class<? extends IFloodlightService >> floodlightService =
	            new ArrayList<Class<? extends IFloodlightService>>();
        floodlightService.add(IFloodlightProviderService.class);
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() 
	{ return MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) 
	{
		return (OFType.PACKET_IN == type 
				&& (name.equals(ArpServer.MODULE_NAME) 
					|| name.equals(DeviceManagerImpl.MODULE_NAME))); 
	}

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) 
	{ return false; }
}

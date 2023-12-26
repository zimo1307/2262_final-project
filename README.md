Overview

	Theme: Implementation of SDN-based network controller logic.
	Context: Networks under a single administrative domain, with potential application across domains.

Implementation
	
 Layer-3 Routing Application:
 
		Goal: Forward traffic to hosts using the shortest, valid path.
		Implementation: Efficient packet switching in a large LAN with multiple switches and potential loops.
		Method: Coding for an SDN controller to compute and install shortest path routes among hosts.
		Tools: Utilizes Bellman-Ford's algorithm based on an adjacency table.
	
 Distributed Load Balancer Application:
 
		Function: Redirects new TCP connections to hosts in a round-robin order.
		Approach: Identifies protocol type, source, and destination addresses of incoming packets, and implements ARP reply sending, TCP address rewriting rules, or TCP RESET sending.

Documentation 

	Files: Include 4 folders.
		Routing.java & ShortestPathSwitching.java for Layer-3 routing.
		LoadBalancer.java for load balancing.

Running the Project

	Pre-requisites: Installation of Mininet.
		Execution:
		Run java -jar FloodlightWithApps.jar -cf $someconfiguration$.prop.
		Start Mininet with a topology configuration in another console.
		Execute commands in the Mininet console after ARPing each host.

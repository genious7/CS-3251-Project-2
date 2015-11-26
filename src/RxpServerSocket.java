import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class RxpServerSocket {
	/** The UDP server socket*/
	private DatagramSocket udpSocket;
	
	/** The RxP server socket port number*/
	private short rxpSrcPort;
	
	/** A list of unaccepted connections*/
	private final BlockingQueue<RxpSocket> unaccepted;
	
	/** A list of all the RxpSockets bound to this serverSocket*/
	private final Map<MultiplexingKey, RxpSocket> connections;
	
	/** A thread responsible for reading all the packets from the UDP port*/
	private final Thread packetReader;
	
	/** True if this {@link RxpServerSocket} is closed, false otherwise*/
	private boolean isClosed;
	
	public RxpServerSocket() {
		connections = new ConcurrentHashMap<>();
		unaccepted = new LinkedBlockingQueue<>();
		packetReader = new Thread(() -> readPacket());
		isClosed = true;
	}
	
	public void listen(int udpPort, int rxpPort) throws SocketException{
		listen(udpPort, (short)rxpPort);
	}
	
	public void listen(int udpPort, short rxpPort) throws SocketException{
		isClosed = false;
		udpSocket = new DatagramSocket(udpPort);
		rxpSrcPort = rxpPort;
		packetReader.start();
	}
	
	/**
	 * Accepts a new connection. This method is blocking and will wait until a
	 * connection is available.
	 * 
	 * @return The socket used by the connection
	 * @throws InterruptedException
	 */
	public RxpSocket accept(){
		try {
			return unaccepted.take();
		} catch (InterruptedException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	public void close() throws IOException{
		// Close all the sockets in parallel, since close is blocking
		connections.values().parallelStream().forEach(i -> {
			try {
				i.close();
			} catch (Exception e) {
			}
		});
		
		// Close the socket
		udpSocket.close();
	}
	
	private void readPacket(){
		// Create a byte array in order to receive and process packets
		byte[] rcvd = new byte[RxpSocket.MAXIMUM_SEGMENT_SIZE];
		DatagramPacket packet = new DatagramPacket(rcvd, RxpSocket.MAXIMUM_SEGMENT_SIZE);
		
		try {
			while (!isClosed) {
				// Receive a packet
				udpSocket.receive(packet);

				// Parse the packet
				RxpPacket rxpPacket = new RxpPacket(rcvd);
				MultiplexingKey key = new MultiplexingKey(packet.getSocketAddress(), rxpPacket.sourcePort);

				// Determine if the packet belongs to an existing connection
				if (connections.containsKey(key)) {
					RxpSocket rxpSocket = connections.get(key);
					// Existing connection, just send the packet to the
					// connection
					rxpSocket.rcvPacket(rxpPacket);
					
					if (rxpSocket.hasException()) {
						// If there is an error, get rid of the socket forcefully.
						connections.remove(key);
						
						// Note that the server application may have not
						// formally accepted the connection, so remove it too.
						unaccepted.remove(rxpSocket);
					}
				} else {
					// The packet is for a new connection. If it is not a SYN
					// packet, discard it.
					if (rxpPacket.isCorrupt() || !rxpPacket.isSyn) continue;
					
					// Create a new connection
					RxpSocket rxpSocket = new RxpSocket(rxpSrcPort, rxpPacket.sourcePort, packet.getSocketAddress(),
							udpSocket, () -> {
								connections.remove(key);
							});
					rxpSocket.rcvPacket(rxpPacket);
					
					connections.put(key, rxpSocket);
					
					// Add the connection to the list of unaccepted connections.
					unaccepted.add(rxpSocket);
				}
			}
		} catch (SocketException e){
			// Note that when the socket is closed, this exception will be thrown. Ignore it.
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * A class that is used as a key for multiplexing packets. The multiplexing
	 * is based on the client's IP address, UDP port, and RxpPort
	 */
	private static final class MultiplexingKey {
		final SocketAddress udpAddress;
		final short rxpPort;

		public MultiplexingKey(SocketAddress address, short port) {
			udpAddress = address;
			rxpPort = port;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj instanceof MultiplexingKey) {
				MultiplexingKey obj2 = (MultiplexingKey) obj;
				return udpAddress.equals(obj2.udpAddress) && (rxpPort == obj2.rxpPort);
			} else {
				return false;
			}
		}
		
		@Override
		public int hashCode() {
			return udpAddress.hashCode() ^ rxpPort;
		}
		
		@Override
		public String toString() {
			return udpAddress + ":" + rxpPort;
		}
	}
}

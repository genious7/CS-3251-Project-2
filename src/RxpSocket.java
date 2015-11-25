import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;

import javax.swing.Timer;

/**
 * An RxpSocket used to provide a reliable connection between two end points.
 * 
 * @author Michael Chen
 *
 */
public class RxpSocket {
	/**
	 * The {@link InputStream} that passes received data to the application
	 * @author Michael Chen
	 */
	private final class RcvStream extends InputStream{
		
		@Override
		public int available() throws IOException {
			return winLength;
		}
		
		@Override
		public int read() throws IOException {
			// If the buffer is empty, return -1
			if (winLength == 0) return -1;
			
			// This is the only method that moves the head, so reading the head
			// doesn't need to be thread safe
			byte nextByte = rcvWindow[winHead];
			
			// Move the head and update the length synchronously
			// Other methods read the head and the length; writes in here need
			// to be synchronized with reads elsewhere.
			synchronized (iStream) {
				if (++winHead == winTotalLength) winHead = 0;				
				winLength--;
			}
			
			return nextByte;
		}
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			// If the buffer is empty, return -1
			if (winLength == 0) return -1;
			
			short bytesToRead;
			synchronized (iStream) {
				bytesToRead = (short) Math.min(len, winLength);
			}
			
			// No need to synchronize this part since it is working on the head
			// while the other thread affects the tail
			if (winTotalLength - winHead >= bytesToRead) {
				// No need to wrap around
				System.arraycopy(rcvWindow, winHead, b, off, bytesToRead);
			} else {
				// Need to wrap around the circular buffer
				int i = winTotalLength - winHead;
				System.arraycopy(rcvWindow, winHead, b, off, i);
				System.arraycopy(rcvWindow, 0, b, off + i, bytesToRead - i);
			}
			
			// Update the head and the length of the circular buffer
			synchronized (iStream) {
				winHead = (short) ((winHead + bytesToRead) % winTotalLength);
				winLength -= bytesToRead;
			}
			
			return bytesToRead;
		}
	}
	
	/**
	 * The {@link OutputStream} used to get the data that must be sent to the
	 * other endpoint.
	 * 
	 * @author Michael Chen
	 *
	 */
	private final class SendStream extends OutputStream{		
		private static final int TIMEOUT_SEND_STREAM = 10;
		
		private final byte[] buffer;
		private short length = 0;
		
		/**
		 * A timer that is used to wait until a packet size equals the maximum
		 * payload size
		 */		
		private Timer streamTimer;
		
		public SendStream() {
			streamTimer = new Timer(TIMEOUT_SEND_STREAM, e -> sendShortPacket());
			streamTimer.setInitialDelay(TIMEOUT_SEND_STREAM);
			streamTimer.setRepeats(false);
			
			buffer = new byte[MAXIMUM_PAYLOAD_SIZE];
			length = 0;
		}
		
		@Override
		public void write(int b) throws IOException {
			if (state != States.ESTABLISHED)
				throw new IllegalStateException("Data can only be sent when the connection has been established");
			
			// Save the byte
			synchronized (this) {
				buffer[length] = (byte) b;
				length++;
			
				// If the buffer is full, send the packet
				if (length == MAXIMUM_PAYLOAD_SIZE){
					RxpPacket packet = new RxpPacket(rxpSrcPort, rxpDstPort, seq, ack, getAvailWindow(), RxpPacket.ACK, buffer);
					sendPacket(packet);
					length = 0;
				} else{
					// Wait to see if more bytes arrive
					if (!streamTimer.isRunning())
						streamTimer.start();
				}
			}
		}
		
		/**
		 * Sends a packet whose length is below the maximum packet length
		 */
		private void sendShortPacket(){
			byte[] payload;
			synchronized (this) {
				payload = new byte[length];
				System.arraycopy(buffer, 0, payload, 0, length);
				length = 0;
				
				RxpPacket packet = new RxpPacket(rxpSrcPort, rxpDstPort, seq, ack, getAvailWindow(), RxpPacket.ACK, payload);
				try {
					sendPacket(packet);
				} catch (IOException e) {
				}
			}
		}
	}
	
	private enum States{
		CLOSED,
		LISTEN,
		SYN_RECEIVED,
		SYN_SENT,
		MIC_RECEIVED,
		ESTABLISHED,
		FIN_WAIT_1,
		FIN_WAIT_2,
		CLOSING,
		TIMED_WAIT,
		CLOSE_WAIT,
		LAST_ACK		
	}  
	
	/**
	 * The timeout before considering unacknowledged packets lost, in
	 * milliseconds
	 */
	private static final int SEND_TIMEOUT = 500;
	
	/** The size of the buffer used to receive data, in bytes */
	private static final int DEFAULT_BUFFER_SIZE = 4096;
	
	/**
	 * The maximum size the packet should have, in bytes. This includes the Rxp
	 * header but not the UDP header
	 */
	protected static final int MAXIMUM_SEGMENT_SIZE = 1472;
	
	/** The maximum size of the actual payload, in bytes*/
	protected static final int MAXIMUM_PAYLOAD_SIZE = MAXIMUM_SEGMENT_SIZE - RxpPacket.HEADER_SIZE;
	
	private short rxpSrcPort, rxpDstPort;
	
	private States state;
	
	private SocketAddress destUdpAddress;
	
	private DatagramSocket srcSocket;
	
	/** The input stream that returns received data*/
	private final InputStream iStream;
	
	/** The output stream that sends the data*/
	private final OutputStream oStream;
	
	/**
	 * A thread that handles receiving packets from the other endpoint
	 */
	private Thread packetReceiver;
	
	/** Resends all packets if they are unacknowledged for too long*/
	private final Timer sendTimeout;
	
	/** A timer for the timed wait state*/
	private final Timer timedWaitTimeout;
	
	/**
	 * A list of the packets that have been sent but not acknowledged.
	 */
	private final Deque<RxpPacket> unacked;
	
	/** A list of packets that are queued but have not been sent yet.*/
	private final Queue<RxpPacket> queuedPackets;
	
	/** A byte array that stores received packets. */
	private byte[] rcvWindow;
	
	/** The head of the receive window*/
	private short winHead;
		
		/** The length of the occupied receive window*/
	private short winLength, destWinLength;
	
	/** The total length of the receive window*/
	private short winTotalLength;
	
	/**
	 * The current sequence number, which must be equal to the sequence number
	 * of the next byte that will be sent.
	 */
	private int seq;
	
	/** The current acknowledgement number, which must be equal to the next expected byte.*/
	private int ack; 
	
	/** The last packet acknowledged by the server*/
	private int lastAck;
	
	/** The random nonce used in the four way handshake. This field is only used by the server.*/
	private byte[] nonce;
	
	/** Any action that must be performed when the connection is closed*/
	private Runnable onClose;
	
	/** A random number generator for general use*/
	private final Random randGenerator;
	
	/** A lock used to create blocking methods*/
	private final Object lock;
	
	/**
	 * Creates a new, unconnected {@link RxpSocket}.
	 */
	public RxpSocket() {
		state = States.CLOSED;
		
		// Initialize the buffer to the default size
		winTotalLength = DEFAULT_BUFFER_SIZE;
		
		// Initialize sending window and timeout timer
		sendTimeout = new Timer(SEND_TIMEOUT, e -> sendTimeout());
		sendTimeout.setInitialDelay(SEND_TIMEOUT);
		
		// Set the timed wait timeout to twice the send timeout
		timedWaitTimeout = new Timer(2 * SEND_TIMEOUT, e -> onTimedWaitTimeout());
		timedWaitTimeout.setRepeats(false);
		timedWaitTimeout.setInitialDelay(2 * SEND_TIMEOUT);
		
		// Create the IO streams
		iStream = new RcvStream();
		oStream = new SendStream();
		
		// Initialize the queue for unacknowledged packets.
		unacked = new ConcurrentLinkedDeque<RxpPacket>();
		
		// Initialize the queue for the send window.
		queuedPackets = new LinkedList<>();
		
		// Initialize the random number generator. Seed it with the system time.
		randGenerator = new Random(System.currentTimeMillis());
		
		// Generate the thread lock
		lock = new Object();
	}
	
	/**
	 * Creates a new {@link RxpSocket} with several default parameters preassigned.
	 * @param srcPort The source RxpPort
	 * @param dstPort The destination RxpPort
	 * @param udpAddress The destination UDP address
	 * @param udpSocket	The source UDP socket
	 * @param onClose An action that should be taken when closing the connection
	 */
	protected RxpSocket(short srcPort, short dstPort, SocketAddress udpAddress, DatagramSocket udpSocket, Runnable onClose){
		// Initialize the timers and other basic variables
		this();
		
		// Set the state
		state = States.LISTEN;
		
		// Set the source and destination ports
		rxpSrcPort = srcPort;
		rxpDstPort = dstPort;
		
		// Use the existing socket
		destUdpAddress = udpAddress;
		srcSocket = udpSocket;
		
		// Initialize the sequence number
		seq = randGenerator.nextInt();
		
		// Define the closing action
		this.onClose = onClose;
	}
	
	/**
	 * Closes the socket and the associated IO streams
	 * @throws IOException
	 */
	public void close() throws IOException{
		// If the connection is not in the established state, throw an error.
		if (state != States.ESTABLISHED && state != States.CLOSE_WAIT) 
			throw new IllegalStateException("The socket must be established before it can be closed");
		
		// Update the state
		state = States.FIN_WAIT_1;
		
		// Send the FIN packet		
		RxpPacket finPkt = new RxpPacket(rxpSrcPort, rxpDstPort, seq, ack, getAvailWindow(), RxpPacket.FIN, new byte[]{0});
		sendPacket(finPkt);
	}
	
	/**
	 * Creates a connection with another endpoint. This function should only be
	 * called by the client.
	 * 
	 * @param address
	 *            The server's IP address and UDP port
	 * @param port
	 *            The server's RxpPort
	 * @param srcUdpPort
	 *            The source UDP port
	 * @throws IOException
	 *             If the connection cannot be established
	 */
	public void connect(SocketAddress address, int port, int srcUdpPort) throws IOException {
		connect(address, (short)port, (short) srcUdpPort);
	}
	
	/**
	 * Creates a connection with another endpoint. This function should only be
	 * called by the client.
	 * 
	 * @param address
	 *            The server's IP address and UDP port
	 * @param port
	 *            The server's RxpPort
	 * @param srcUdpPort
	 *            The source UDP port
	 * @throws IOException
	 *             If the connection cannot be established
	 */
	public void connect(SocketAddress address, short port, short srcUdpPort) throws IOException{
		// Verify that the socket is not already in use.
		if (state != States.CLOSED) {
			throw new IllegalStateException("This socket is already connected");
		}
		
		// Set the source and destination RxpPorts
		rxpSrcPort = (short) randGenerator.nextInt();
		rxpDstPort = port;
		
		// Create a new UDP socket
		destUdpAddress = address;
		srcSocket = new DatagramSocket(srcUdpPort);
		
		// Initialize the sequence number
		seq = randGenerator.nextInt();
		
		// Set the socket to close the udp socket at the end of the connection
		onClose = new Runnable() {
			@Override
			public void run() {
				srcSocket.close();
			}
		};
		
		// Update the state
		state = States.SYN_SENT;
		
		// Start a thread to handle received packets
		packetReceiver = new Thread(() -> {
			// Create a byte array in order to receive and process packets
			byte[] rcvd = new byte[MAXIMUM_SEGMENT_SIZE];
			DatagramPacket packet = new DatagramPacket(rcvd, MAXIMUM_SEGMENT_SIZE);

			while (state != States.CLOSED) {
				try {
					srcSocket.receive(packet);
					RxpPacket parsedPacket = new RxpPacket(rcvd);
					rcvPacket(parsedPacket);
				} catch (IOException e) {
					continue; // If an error occurs while reading the packet,
								// just drop the packet.
				}
			}
		});
		packetReceiver.start();	
		
		// Send the connection request
		RxpPacket synPkt = new RxpPacket(rxpSrcPort, rxpDstPort, seq, ack, getAvailWindow(), RxpPacket.SYN, new byte[]{0});
		sendPacket(synPkt);
		
		synchronized (lock) {
			// Wait until the connection is established before returning
			try {
				while (state != States.ESTABLISHED) {
					lock.wait();
				}
			} catch (InterruptedException e) {
			}
		}		
	}
	
	/**
	 * Returns the {@link InputStream} that can be used to read data from the socket.
	 * @return The input stream
	 */
	public InputStream getInputStream(){
		return iStream;
	}
	
	/**
	 * Returns the {@link OutputStream} used to send data through the socket.
	 * @return The output stream
	 */
	public OutputStream getOutputStream(){
		return oStream;
	}

	/**
	 * Sets the size of the receiving buffer.
	 * @param size The size of the buffer, in shorts.
	 */
	public void setBufferSize(short size){
		// If you try to change the size of an open connection, throw an error.
		if (state != States.CLOSED)
			throw new IllegalStateException("Can't change the buffer size of an open connection");
		
		// If the buffer size is less than the segment size, throw an error.
		if (size < MAXIMUM_PAYLOAD_SIZE)
			throw new IllegalArgumentException("The buffer size cannot be smaller than the payload size");
		
		winTotalLength = size;
	}
	
	/**
	 * Sets the size of the receiving window
	 * 
	 * @param segmentSize
	 *            The number of segments in the window
	 */
	public void setWindowSize(int segmentSize){	
		setBufferSize((short) (segmentSize * MAXIMUM_PAYLOAD_SIZE));
	}
	
	/**
	 * Allocated the receive buffer
	 */
	private void allocateRcvBuffer(){
		rcvWindow = new byte[winTotalLength];
		winHead = 0;
		winLength = 0;
	}
	
	/**
	 * Checks the hash returned by the client. If it is not correct, the
	 * connection is closed.
	 * 
	 * @param packet
	 * @throws IOException 
	 */
	private void checkHash(RxpPacket packet) throws IOException {
		if (packet.payloadLength != 32) 
			throw new IllegalStateException("checkHash - The length of the packet is incorrect");
		
		if (!packet.isAck|packet.isNack|packet.isFin|packet.isSyn)
			throw new IllegalStateException("checkHash - Illegal packet flags");
		
		// Update the acknowledgement
		ack += packet.payloadLength;
		
		// Calculate the hash of the nonce.
		byte[] hash = hash(nonce);
		
		// Compare the calculated hash with the true value
		if (Arrays.equals(hash, packet.payload)){
			// Hash matches, allocate resources
			state = States.ESTABLISHED;
			allocateRcvBuffer();
		}else{
			// Bad connection attempt, close the connection forcefully
			onTimedWaitTimeout();
		}
		
		// Send the ack
		sendAck();
	}
	
	/**
	 * Returns the available space in the receive window. This method is thread safe
	 * @return The available space of this enpoint's receive window, in bytes
	 */
	private short getAvailWindow(){
		short winSize;
		
		synchronized (iStream) {
			winSize = (short) (winTotalLength - winLength);
		}
		
		return winSize;
	}

	/**
	 * Handle the acknowledgement packet.
	 * @param packet
	 */
	private void handleAck(RxpPacket packet){
		// If the latest acknowledgement packet is valid, do nothing.
		if (lastAck == packet.ack) return;
		
		//TODO: Check that ACK makes sense using the lastAck and the number of packets in flight
		
		// Boolean to check if the timer should be reset
		boolean hasChanged = false;
		lastAck = packet.ack;
		
		// Remove all acknowledged packets
		// Note the implementation assumes an ordered list
		while (!unacked.isEmpty()){
			RxpPacket nextUnacked = unacked.getFirst();
			if(lastAck - nextUnacked.seq > 0){
				hasChanged = true;
				unacked.removeFirst();
			} else{
				break;
			}
		}
		
		if (hasChanged){
			// Packets have been acknowledged, update the queues
			processQueues();
			
			// Resets the send timeout timer
			if (unacked.isEmpty()){
				sendTimeout.stop();
			} else{
				sendTimeout.restart();
			}
		}

		// On some states, an Ack can cause a state change. This should only
		// happen when all prior packets have been acknowledged.
		if (unacked.isEmpty()) {
			if (state == States.MIC_RECEIVED){ 
				state = States.ESTABLISHED;
				
				// Note that the client buffer gets allocated on the transition from
				// MIC_RECEIVED to ESTABLISHED
				allocateRcvBuffer();
				
				// Note that the client's connect() method does not return until
				// the state has changed to established. Notify the connect method.
				synchronized (lock) {
					lock.notifyAll();
				}
			} else if (state == States.CLOSING) state = States.TIMED_WAIT;
			else if (state == States.LAST_ACK) state = States.CLOSED;
			else if (state == States.FIN_WAIT_1) state = States.FIN_WAIT_2;
		}
	}
	
	/**
	 * Handles all received FIN packets.
	 * @param packet The packet received. Must have the FIN flag.
	 * @throws IOException If the acknowledgement cannot be sent.
	 */
	private void handleFin(RxpPacket packet) throws IOException{
		// Fin's should not have any other flag set
		if (packet.isAck||packet.isSyn||!packet.isFin||packet.isNack)
			throw new IllegalStateException("rcvFin - FIN packets should not have other flags set");
		
		// Check that the payload length is correct
		if (packet.payloadLength != 1)
			throw new IllegalStateException("rcvFin - FIN packets must have a length of one");
		
		// Send the acknowledgement
		ack += packet.payloadLength;
		sendAck();
		
		// Change the state if applicable. Since duplicate packets have already
		// been removed when this method is called, an invalid FIN will throw an
		// exception.
		if (state == States.FIN_WAIT_1) state = States.CLOSING;
		else if (state == States.FIN_WAIT_2) state = States.TIMED_WAIT;
		else if (state == States.ESTABLISHED) state = States.CLOSE_WAIT;
		else throw new IllegalStateException("rcvFin - ???");
	}
	
	/**
	 * Handles all received SYN packets
	 * @param packet The packet received
	 * @throws IOException If the response cannot be sent
	 */
	private void handleSyn(RxpPacket packet) throws IOException{
		if (state == States.SYN_SENT){
			// The client has received a SYN packet. Send the authentication
			// hash.
			rcvNonce(packet);
		} else if (state == States.LISTEN) {
			// The server has received a SYN packet. Send the nonce to the
			// client.
			sndNonce(packet);
		}else{
			throw new IllegalStateException("handleSyn - A syn packet was received while the state was " + state);
		}
	}
	
	/**
	 * Gets the SHA256 of the input bytes
	 * @param nonce The nonce used during the handshake
	 * @return The hash, as a byte array.
	 */
	private byte[] hash(byte[] nonce){
		byte[] hash;
		
		// Parse the hash
		try {
			MessageDigest hasher = MessageDigest.getInstance("SHA-256");
			hasher.update(nonce);
			hash = hasher.digest();	
		} catch (NoSuchAlgorithmException e) {
			throw new UnsupportedOperationException(e);
		}
		
		return hash;
	}
	
	/**
	 * Called whenever a timeout occurs on the timed wait timeout.
	 */
	private void onTimedWaitTimeout() {
		// Stop any timers that are still running
		timedWaitTimeout.stop();
		sendTimeout.stop();
		
		// Set the state to closed
		state = States.CLOSED;

		// Close the socket. This will cause the termination of the
		// packetReceiver Thread
		onClose.run();
	}

	/**
	 * Receives data from the network and places it on the buffer.
	 * @param packet The packet received
	 * @throws IOException If the acknowledgement cannot be sent
	 */
	private void rcvData(RxpPacket packet) throws IOException {
		// If there is no space on the buffer, drop the packet.
		if (packet.payloadLength > getAvailWindow())
			return;
		
		// Calculate the available length
		int tail;
		synchronized (iStream) {
			tail = winHead + winLength;
			
			// Check if the array is already wrapping around
			if (tail >= winTotalLength){
				tail -= winTotalLength;
			}
		}
		
		int spaceAfterTail = winTotalLength - tail;
		
		// Copy the payload to the window
		// No need to synchronize this part since it writes on the tail while
		// the other thread gets data from the head.
		if (packet.payloadLength < spaceAfterTail || tail < winHead){
			System.arraycopy(packet.payload, 0, rcvWindow, tail, packet.payloadLength);
		} else{
			System.arraycopy(packet.payload, 0, rcvWindow, tail, spaceAfterTail);
			System.arraycopy(packet.payload, spaceAfterTail, rcvWindow, 0, packet.payloadLength - spaceAfterTail);
		}
		
		synchronized (iStream) {
			// Update the window length
			winLength += packet.payloadLength;
		}
		
		// Update the acknowledgement
		ack += packet.payloadLength;
		
		// Send the acknowledgement
		sendAck();
	}

	/**
	 * Receives a packet containing a random nonce, parses it, validates it, and
	 * sends the hash back to the server. The method will also change the state
	 * if required.
	 * 
	 * @param packet
	 *            The packet received.
	 * @throws IOException
	 *             If the packet received was invalid or if a reply cannot be
	 *             sent.
	 */
	private void rcvNonce(RxpPacket packet) throws IOException, IllegalStateException{
		// Check that the packet flags are correct
		if (!packet.isAck||!packet.isSyn||packet.isFin||packet.isNack)
			throw new IllegalStateException("rcvNonce - The packet received has invalid flags");
		
		// Check that the payload length is correct
		if (packet.payloadLength != 4)
			throw new IllegalStateException("rcvNonce - The payload length is incorrect ");
		
		// At this point, the packet is validated. Initialize the acknowledgement value.
		ack = packet.seq + packet.payloadLength;

		// Calculate the hash for the provided nonce
		byte[] hash = hash(packet.payload);
		
		// Create the new packet that should be sent to the other endpoint
		RxpPacket hashPkt = new RxpPacket(rxpSrcPort, rxpDstPort, seq, ack, getAvailWindow(), RxpPacket.ACK, hash);
		sendPacket(hashPkt);
		
		// Update the state
		state = States.MIC_RECEIVED;
	}

	/**
	 * Sends an acknowledgement stating the currently expected value
	 * @throws IOException 
	 */
	private void sendAck() throws IOException{
		RxpPacket ackPkt = new RxpPacket(rxpSrcPort, rxpDstPort, seq, ack, getAvailWindow(), RxpPacket.ACK, new byte[]{});
		DatagramPacket packet = new DatagramPacket(ackPkt.asByteArray(), ackPkt.getTotalLength(), destUdpAddress);
		srcSocket.send(packet);
	}

	/**
	 * Sends a negative acknowledgement to the other end point
	 * @throws IOException If the negative acknowledgement cannot be sent
	 */
	private void sendNack() throws IOException{
		RxpPacket nack = new RxpPacket(rxpSrcPort, rxpDstPort, seq, ack, getAvailWindow(), RxpPacket.NACK, new byte[]{});
		DatagramPacket packet = new DatagramPacket(nack.asByteArray(), nack.getTotalLength(), destUdpAddress);
		srcSocket.send(packet);
	}

	/**
	 * Sends the packet to the other endpoint.
	 * @param packet
	 * @throws IOException 
	 */
	private void sendPacket(RxpPacket packet) throws IOException{
		// Increase the sequence number
		seq += packet.payloadLength;
		
		// Queue the packet on the packet queue
		queuedPackets.add(packet);
		
		// If there is space in the sliding window, add the packet to the
		// sliding window and send the packet.
		processQueues();
	}
	
	/**
	 * Checks how many packets are currently unaccounted. If the amount of
	 * packets "in the air" is less than the maximum supported by the other end
	 * point, send additional packets.
	 */
	private void processQueues(){
		short winSize = getAvailWindow();
		
		// Note that the construction of the loop condition ensures that there
		// is always at least one packet in the network.
		while ((Integer.compareUnsigned(unacked.size() * MAXIMUM_PAYLOAD_SIZE, destWinLength) < 0 || unacked.isEmpty())
				&& !queuedPackets.isEmpty()) {
			RxpPacket nextPacket = queuedPackets.remove();

			// Update the packet flags
			nextPacket.setAck(ack);
			nextPacket.setWindowSize(winSize);
			
			// Send the packet
			DatagramPacket udpPkt = new DatagramPacket(nextPacket.asByteArray(), nextPacket.getTotalLength(), destUdpAddress);
			try {
				srcSocket.send(udpPkt);
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			// Add the packet to the list of unacknowledged packets
			unacked.addLast(nextPacket);
		}

		// Start the timer if it isn't running already and if there are packets on the network.
		if (!sendTimeout.isRunning() && !unacked.isEmpty()) sendTimeout.start();
	}
	
	/**
	 * Called every time a timeout occurs; must resend all packets in the send window
	 */
	private void sendTimeout(){
		// If the list is empty for some reason, stop the timer
		if (unacked.isEmpty()) sendTimeout.stop();
		
		// Resends all unacknowledged packets
		short winSize = getAvailWindow();
		
		// Note that the amount of packets in the unacked queue should not
		// exceed the receiver buffer - see processQueue.
		for (RxpPacket rxpPacket : unacked) {
			rxpPacket.setAck(ack);
			rxpPacket.setWindowSize(winSize);
			DatagramPacket udpPkt = new DatagramPacket(rxpPacket.asByteArray(), rxpPacket.getTotalLength(), destUdpAddress);
			try {
				srcSocket.send(udpPkt);
			} catch (IOException e) {}
		}
	}

	/**
	 * Sends a random four byte nonce to the client.
	 * @param packet The SYN packet sent by the client
	 * @throws IOException If the nonce cannot be sent to the client.
	 */
	private void sndNonce(RxpPacket packet) throws IOException {
		// Check that the packet flags are correct
		if (packet.isAck || !packet.isSyn || packet.isFin || packet.isNack)
			throw new IllegalStateException("rcvNonce - The packet received has invalid flags");
		
		// Check that the payload length is correct
		if (packet.payloadLength != 1) throw new IllegalStateException("rcvNonce - The payload length is incorrect ");
		
		// At this point, the packet is validated. Initialize the acknowledgement value.
		ack = packet.seq + packet.payloadLength;
		
		// Create a random nonce
		nonce = new byte[4];
		randGenerator.nextBytes(nonce);

		// Create the new packet that should be sent to the other endpoint
		RxpPacket hashPkt = new RxpPacket(rxpSrcPort, rxpDstPort, seq, ack, getAvailWindow(),
				(short) (RxpPacket.ACK | RxpPacket.SYN), nonce);
		sendPacket(hashPkt);

		// Update the state
		state = States.SYN_RECEIVED;
	}

	/**
	 * Processes a packet, sending any required replies through the network.
	 * @param packet The {@link RxpPacket} received by this endpoint
	 * @throws IOException If a reply cannot be sent successfully
	 */
	protected void rcvPacket(RxpPacket packet) throws IOException {	
		// If the packet is corrupted, it should be dropped.
		// A NACK should be sent to the other endpoint.
		if (packet.isCorrupt()) {
			sendNack();
			System.err.println("Corrupted packet received");
			return;
		}

		// If the packet should is not addressed to the current port, drop it.
		if (packet.destPort != rxpSrcPort) return;
		
		// If the packet is aimed at this client and it is not corrupted, update
		// the field that remembers the destination's available receive window.
		// Note that this field will always equal the last received packet; this
		// is intentional.
		destWinLength = packet.windowSize;
		
		// If the packet has the acknowledgement flag, update the receive
		// buffer. Note that state changes caused by ACK packets are handled
		// internally.
		if (packet.isAck) handleAck(packet);
		
		// If the packet is a NACK, resend all unacked packets.
		if (packet.isNack){
			sendTimeout.restart();
			sendTimeout();
		}
		
		// If the packet carries some data (or a flag), discard it if it has
		// already been acknowledged and resend the acknowledgement. This line
		// handles duplicate packages.
		if ((packet.isSyn || packet.isFin || packet.payloadLength > 0)
				&& (ack - (packet.seq + packet.payloadLength - 1) > 0)
				&& (state != States.LISTEN && state != States.SYN_SENT)) {
			sendAck();
			return;
		}
		
		// If the packet is a FIN packet, handle it. Note that this function
		// assumes that all duplicate packets have been removed.
		if (packet.isFin){
			handleFin(packet);
			if (state == States.TIMED_WAIT)
				timedWaitTimeout.start();
			return;
		}
		
		// If the packet is a SYN packet, handle it. Note that this function
		// assumes that duplicate packets have been removed.
		if (packet.isSyn){
			handleSyn(packet);
			return;
		}
		
		// If the server is receiving the hash in the SYN_RECEIVED state, handle
		// that case here.
		if (state == States.SYN_RECEIVED && packet.payloadLength > 0){
			checkHash(packet);
			return;
		}
		
		// If the packet has a non zero payload and it is not a SYN or FIN
		// packet, process the data here.
		if (state == States.ESTABLISHED && packet.payloadLength > 0){
			rcvData(packet);
		}
	}	
}

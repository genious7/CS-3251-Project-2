import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A single Rxppacket. Each instance of this class is an immutable object that
 * corresponds to one packet and its associated headers.
 * 
 * @author Michael Chen
 *
 */
public class RxpPacket {
	public final static int HEADER_SIZE = 20; // 20 Bytes
	public final static short ACK = 1;
	public final static short NACK = 1 << 1;
	public final static short SYN = 1 << 2;
	public final static short FIN = 1 << 3;

	public final short sourcePort;
	public final short destPort;
	public final int seq;
	public int ack;
	public final short payloadLength;
	public short windowSize;
	public final boolean isAck;
	public final boolean isNack;
	public final boolean isSyn;
	public final boolean isFin;
	public final byte[] payload;
	private short checksum;

	/**
	 * Parses a UDP datagram into an RxpPacket.
	 * 
	 * @param packet
	 *            The received datagram as a byte array
	 */
	public RxpPacket(byte[] packet) {
		// Wrap the array in a byte buffer
		ByteBuffer buffer = ByteBuffer.wrap(packet);

		// Parse the header parameters
		sourcePort = buffer.getShort();
		destPort = buffer.getShort();
		seq = buffer.getInt();
		ack = buffer.getInt();
		payloadLength = buffer.getShort();
		windowSize = buffer.getShort();

		// Parse the flags
		short flags = buffer.getShort();
		isAck = (flags & ACK) != 0;
		isNack = (flags & NACK) != 0;
		isSyn = (flags & SYN) != 0;
		isFin = (flags & FIN) != 0;

		checksum = buffer.getShort();

		// Read the payload
		payload = new byte[payloadLength];
		buffer.get(payload);
	}

	/**
	 * Creates a new RxpPacket from the individual parameters
	 * 
	 * @param srcPort
	 *            The source RxpPort
	 * @param dstPort
	 *            The destination RxpPort
	 * @param seq
	 *            The sequence number of the first byte in this packet
	 * @param ack
	 *            The acknowledgement number
	 * @param windowSize
	 *            The available size in the receive window
	 * @param flags
	 *            The flags set in this packet
	 * @param payload
	 *            The payload, as a byte array
	 */
	public RxpPacket(short srcPort, short dstPort, int seq, int ack, short windowSize, short flags, byte[] payload) {
		this.sourcePort = srcPort;
		this.destPort = dstPort;
		this.seq = seq;
		this.ack = ack;
		this.windowSize = windowSize;
		isAck = (flags & ACK) != 0;
		isNack = (flags & NACK) != 0;
		isSyn = (flags & SYN) != 0;
		isFin = (flags & FIN) != 0;

		// Check that the payload length is under the allowed maximum
		if (payload.length > RxpSocket.MAXIMUM_PAYLOAD_SIZE) throw new IllegalArgumentException();

		this.payloadLength = (short) payload.length;		
		this.payload = Arrays.copyOf(payload, payloadLength);
		this.checksum = calculateChecksum();
	}

	/**
	 * Checks if the packet checksum is valid
	 * 
	 * @return True if the packet is corrupted, false otherwise
	 */
	public boolean isCorrupt() {
		return calculateChecksum() != checksum;
	}

	/**
	 * Returns the complete packet with the header structure as a byte array.
	 * 
	 * @return The packet as a byte array.
	 */
	public byte[] asByteArray() {
		// Parse the flags
		short flags = 0;
		flags |= isAck ? ACK : 0;
		flags |= isNack ? NACK : 0;
		flags |= isSyn ? SYN : 0;
		flags |= isFin ? FIN : 0;
		
		// Calculate the checksum
		checksum = calculateChecksum();

		ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE + payloadLength);
		buffer.putShort(sourcePort);
		buffer.putShort(destPort);
		buffer.putInt(seq);
		buffer.putInt(ack);
		buffer.putShort(payloadLength);
		buffer.putShort(windowSize);
		buffer.putShort(flags);
		buffer.putShort(checksum);
		buffer.put(payload);

		return buffer.array();
	}
	
	public int getTotalLength(){
		return HEADER_SIZE + payloadLength;
	}
	
	/**
	 * Calculates the checksum based on the packet header and the packet
	 * contents.
	 * 
	 * @return The 16-bit checksum
	 */
	private short calculateChecksum() {
		short c = 0;

		// Add every value to the checksum except the received checksum
		c += sourcePort;
		c += destPort;
		c += (seq) + (seq >> 16);
		c += (ack) + (ack >> 16);
		c += payloadLength;
		c += windowSize;
		c += isAck ? ACK : 0;
		c += isNack ? NACK : 0;
		c += isFin ? FIN : 0;
		c += isSyn ? SYN : 0;

		// For every short in the payload, perform the addition
		for (int i = 0; i < (payload.length & ~1); i += 2) {
			c += (payload[i] << 8) + payload[i + 1];
		}

		// If the payload has an odd number of bytes, handle the last case
		// separately
		if ((payload.length & 0x1) != 0) c += payload[payload.length - 1];

		return c;
	}

	/**
	 * Sets the window size
	 * @param windowSize
	 */
	public void setWindowSize(short windowSize){
		this.windowSize = windowSize;
	}
	
	/**
	 * Sets the acknowledgement number
	 * @param ack
	 */
	public void setAck(int ack){
		this.ack = ack;
	}
	
	
}

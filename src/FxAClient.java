import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Scanner;

import java.nio.ByteBuffer;
import java.nio.file.*;

public class FxAClient {
	
	public static void main(String[] args) throws IOException {
		final int serverUdpPort;
		final int netEmuUdpPort;
		final InetSocketAddress netIP;
		
		//Check if there are enough command line arguments and make sure they are valid
		if (args.length != 3){
			throw new IllegalArgumentException("FxA may only take in exactly 3 inputs: Port, NetEmu IP, NetEmu Port.");
		}
				
		try {
			//Parse the port and netemu port inputs
            serverUdpPort = Integer.parseInt(args[0]);
            netEmuUdpPort = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Arguments " + args[0] + " and " + args[2] +  " must be integers.");
            return;
        }
		
		//Convert the string IP input into a valid IP address
		netIP = new InetSocketAddress(args[1], netEmuUdpPort);

		// Create a new RxpSocket and get the corresponding input and output
		// streams
		final RxpSocket socket = new RxpSocket();
		final OutputStream writer = socket.getOutputStream();
		final InputStream reader = socket.getInputStream();
		
		boolean connected = false;
		
		Scanner scan = new Scanner(System.in);
		try {
			while (true) {
				// Split the input into the command and the arguments
				String input = scan.nextLine();
				String[] split = input.split("\\s+", 2);

				// Check that there is a command
				if (split.length == 0) continue;
				
				// Check that the port is still open
				if (socket.isClosed() && connected) break;

				switch (split[0]) {
				case "connect":
					if (!connected) {
						socket.connect(netIP, 2300, serverUdpPort);
						connected = true;
					} else {
						System.err.println("You are already connected");
					}
					break;
				case "disconnect":
					if (connected) {
						socket.close();
						scan.close();
					}
					System.out.println("Socket closed. Terminating program");
					return;
				case "get":
					if (connected && split.length > 1) {
						String fileName = split[1];

						// Send null terminated get command.
						String getRequest = "get:" + fileName + "\0";
						writer.write(getRequest.getBytes());
						
						// Receive the length of the file as and parse it into
						// an int.
						byte fileSizeTmp[] = new byte[4];
						readNBytes(reader, fileSizeTmp);
						ByteBuffer tmp = ByteBuffer.wrap(fileSizeTmp);
						int fileSize = tmp.getInt();
						
						if(fileSize == -1) {
							System.out.println("File does not exist!");
						} else {
							// Create the destination byte array
							byte fileContent[] = new byte[fileSize];
							readNBytes(reader, fileContent);
	
							// Write the file
							File file = new File(fileName + ".bak");
							Files.write(file.toPath(), fileContent, StandardOpenOption.CREATE);
						}
					} else if (split.length == 1) {
						System.out.println("Get requires a second input!");
					} else {
						System.out.println("You must be connected to get!");
					}
					break;
				case "put":
					if (connected && split.length == 2) {
						try {
							File inputFile = new File(split[1]);
							byte fileContent[] = Files.readAllBytes(inputFile.toPath());
	
							// Send the null terminated put command
							String putRequest = "put:" + split[1] + "\0";
							writer.write(putRequest.getBytes());
	
							// Send the file length
						
							byte[] lengthTmp = new byte[4];
							ByteBuffer buffer = ByteBuffer.wrap(lengthTmp);
							buffer.putInt(fileContent.length);
							writer.write(lengthTmp);
	
							// Send the file
							writer.write(fileContent);
						} catch (NoSuchFileException e){
							System.out.println("File does not exist!");
						}
					} else if (split.length == 1) {
						System.err.println("Put requires a second input!");
					} else {
						System.err.println("You must be connected to put a file into the server!");
					}
					break;
				case "window":
					if (split.length == 2) {
						try {
							socket.setWindowSize(Integer.parseInt(split[1]));
						} catch (NumberFormatException e) {
							System.err.println("The argument " + split[1] + " must be an integer.");
						} catch (IllegalStateException e) {
							System.err.println("The window size cannot be reduced since it is currently in use");
						}
					} else {
						System.err.println("Window requires a second input!");
					}
					break;
				default:
					System.err.println("Command not recognized");
					break;
				}
			}	
		} catch (SocketException e) {
		}
		System.out.println("Terminated Gracefully");
	}
	
	/**
	 * Reads bytes until the provided byte array is full.
	 * 
	 * @param in
	 *            The source input stream
	 * @param array
	 *            The destination array
	 * @throws IOException
	 *             if the input stream cannot be read
	 */
	private static void readNBytes(InputStream in, byte[] array) throws IOException{
		int offset = 0;
		while (offset < array.length){
			if (in.available() > 0){
				offset += in.read(array, offset, array.length - offset);
			} else{
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {}
			}
		}
	}
	
	/**
	 * Takes in the pathname of a file and converts the contents of the file into
	 * a byte array.
	 * 
	 * 
	 * @param String pathName
	 *            The path of the file. 
	 * @throws NoSuchFileException
	 *             If the file does not exist or couldn't be found.
	 * @throws IOException
	 * 				If the file could not be read from.
	 */
	public static byte [] getFileBytes(String pathName){
		Path path = Paths.get(pathName);
		byte[] data = null;
		try {
			data = Files.readAllBytes(path);
		} catch (NoSuchFileException e) {
			System.out.println("File does not exist!");
			return null;
		} catch (IOException e) {
			System.err.println("File could not be read.");
			e.printStackTrace();
		}
		return data;
	}
}

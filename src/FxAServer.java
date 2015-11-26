import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Scanner;


public class FxAServer {
	
	public static void main(String[] args) throws IOException {
		
		final int srcUdpPort;
		
		//Check if there are enough command line arguments and make sure they are valid
		if (args.length != 3){
			throw new IllegalArgumentException("FxA may only take in exactly 3 inputs: Port, NetEmu IP, NetEmu Port.");
		}
		
		//Parse the port and netemu port inputs
		try {
            srcUdpPort = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Arguments " + args[0] + " and " + args[2] +  " must be integers.");
            return;
        }
		
		// Create and bind the server socket
		final RxpServerSocket serverSocket = new RxpServerSocket();
		serverSocket.listen(srcUdpPort, 2300);
		
		final Scanner textInput = new Scanner(System.in);
		new Thread(()->{
			String command = textInput.nextLine();
			String[] split = command.toString().split(":");
			switch (split[0]) {
			case "window":
				if (split.length != 2){
					System.err.println("The window command has two parameters");
				} else {
					try{
						serverSocket.setWindowSize(Integer.parseInt(split[1]));
					} catch (NumberFormatException e){
						System.err.println("The second parameter must be a number");
					}					
				}
				break;
			case "terminate":
				try {
					serverSocket.close();
					textInput.close();
				} catch (Exception e) {}
				break;
			default:
				System.err.println("Command not recognized");
				break;
			}
		}).start();
		
		RxpSocket socket;

		while (true) {
			try {
				// Accept a connection
				socket = serverSocket.accept();
				InputStream reader = socket.getInputStream();
				OutputStream writer = socket.getOutputStream();

				// For each command
				while (!socket.isClosed()) {

					// Read the null terminated command
					StringBuilder commandBuilder = new StringBuilder();
					int nextChar;
					while ((nextChar = reader.read()) != 0) {
						if (nextChar == -1) try {
							Thread.sleep(50);
						} catch (InterruptedException e) {
						}
						else {
							commandBuilder.append((char) nextChar);
						}
					}

					// Split the command into a filename and an action
					String[] split = commandBuilder.toString().split(":");

					// Validate the command, move on to the next client if it is
					// invalid.
					if (split.length != 2) {
						socket.close();
						continue;
					}

					switch (split[0]) {
					case "get":
						File inputFile = new File(split[1]);
						byte fileContent[] = Files.readAllBytes(inputFile.toPath());

						// Send the file length
						byte[] lengthTmp = new byte[4];
						ByteBuffer buffer = ByteBuffer.wrap(lengthTmp);
						buffer.putInt(fileContent.length);
						writer.write(lengthTmp);

						// Send the file
						writer.write(fileContent);
						break;
					case "put":
						String fileName = split[1];

						// Receive the length of the file as and parse it into
						// an int.
						byte fileSizeTmp[] = new byte[4];
						readNBytes(reader, fileSizeTmp);
						ByteBuffer tmp = ByteBuffer.wrap(fileSizeTmp);
						int fileSize = tmp.getInt();

						// Create the destination byte array
						byte fileReceived[] = new byte[fileSize];
						readNBytes(reader, fileReceived);

						// Write the file
						File file = new File(fileName + ".bak");
						Files.write(file.toPath(), fileReceived, StandardOpenOption.CREATE);
						break;
					default:
						socket.close();
						continue;
					}
				}
			} catch (SocketException e) {
				// This connection was closed by the client. Proceed to the next
				// one
			}
			System.out.println("Terminated with client gracefully");
			if (serverSocket.isClosed()) break;
		}
		System.out.println("Socket closed. Terminating Program");
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
			System.err.println("File could not be read!");
			e.printStackTrace();
		}
		return data;
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
}

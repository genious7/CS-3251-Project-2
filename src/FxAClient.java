import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Scanner;
import java.util.ArrayList;
import java.nio.file.*;

public class FxAClient {

	private static RxpServerSocket serverSocket;
	private static RxpSocket socket;

	
	public static void main(String[] args) throws Exception {
		
		int port = 0;
		InetAddress netIP = null;
		int netPort = 0;
		boolean connected = false;
		//Check if there are enough command line arguments and make sure they are valid
		
		if (args.length != 3){
			throw new IllegalArgumentException("FxA may only take in exactly 3 inputs: Port, NetEmu IP, NetEmu Port.");
		}
		
		//Parse the port and netemu port inputs
		
		try {
            port = Integer.parseInt(args[0]);
            netPort = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Arguments " + args[0] + " and " + args[2] +  " must be integers.");
            System.exit(1);
        }
		//Convert the string IP input into a valid IP address
		try {
			netIP = InetAddress.getByName(args[1]);
		} catch (UnknownHostException e) {
			System.err.println(e);
		}
		
		Scanner scan = new Scanner(System.in);
		socket = new RxpSocket();
		OutputStream writer = socket.getOutputStream();
		
		while (true){
			
			//Split the command via a space delimiter
			
			String input = scan.nextLine();
			String [] split = input.split("\\s+");
			
			//Connect request
			
			if(split[0].equals("connect")) {
				if(!connected) {
					socket.connect(new InetSocketAddress(netIP, netPort), 2300, port);
					writer = socket.getOutputStream();
					connected = true;
				} else {
					System.out.println("You are already connected!");
				}
				
				//Put request
				
			} else if(split[0].equals("put")) {
				if(connected){
					if(split.length>1) {
						String fileName = split[1];
						String filePath = System.getProperty("user.dir") + "\\" + fileName;
						System.out.println(filePath);
						byte[] file = getFileBytes(filePath);
						
						//writer.write("Putting:x".getBytes());
						//writer.write(fileName.getBytes());
						writer.write(file);
					} else {
						System.out.println("Put requires a second input!");
					}
				} else {
					System.out.println("You must be connected to put!");
				}
				
				//Get request
				
			} else if(split[0].equals("get")) {
				if(connected) {
					if(split.length>1) { 
						int count = 0;
						String fileName = split[1];
						String getRequest = "get:" + fileName; 	
						writer.write(getRequest.getBytes());
						InputStream reader = socket.getInputStream();
						String getResults = "";
						
						//Receive the file that was get requested and store it in a string to be saved to file.
						
						while (true){				
							if (reader.available() > 0){
								byte buffer[]  = new byte[reader.available()];
								reader.read(buffer);
								
								getResults+=(new String(buffer));
								
								System.out.println(new String(buffer));
								
							} else {
								try {
									count++;
									Thread.sleep(100);
									if(count > 10) {
										break;
									}
								} catch (InterruptedException e) {
									e.printStackTrace();
								}
							}
						}
						
						File file = new File(System.getProperty("user.dir") + "\\" + fileName);
	
						// Create the file if it doesn't exist.
						
						if (!file.exists()) {
							file.createNewFile();
						}
						//Save to a file using filewriter and bufferedwriter.
						
						FileWriter fw = new FileWriter(file.getAbsoluteFile());
						BufferedWriter bw = new BufferedWriter(fw);
						bw.write(getResults);
						bw.close();
						
					} else {
						System.out.println("Get requires a second input!");
					}
				} else {
					System.out.println("You must be connected to get!");
				}
				
				// Close request
				
			} else if (split[0].equals("close")) {
				if(connected) {
					socket.close();
					System.out.println("Finished Gracefully");
					scan.close();
					return;
				} else {
					System.out.println("You are not connected!");
				}
				
				// Window size change request
				
			} else if (split[0].equals("window")) {
				if(!connected) {
					if(split.length>1) { 
						try {
				            int windowSize = Integer.parseInt(args[0]);
				            socket.setWindowSize(windowSize);
				        } catch (NumberFormatException e) {
				            System.err.println("Argument " + split[1] + " must be an integer.");
				            System.exit(1);
				        }
					} else {
						System.out.println("You cannot change the window size of an established connection!");
					}
				} else {
					System.out.println("Window requires a second input!");
				}
			} else {
				writer.write(input.getBytes());
			}

			//String line = scan.nextLine();
			//writer.write(line.getBytes());
			
			
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

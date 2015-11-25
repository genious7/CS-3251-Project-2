import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;


public class FxAServer {
	
	private static RxpServerSocket serverSocket;
	private static RxpSocket socket;

	
	public static void main(String[] args) throws Exception {
		
		String clientSentence = null;
		String capitalizedSentence = null;
		int port = 0;
		InetAddress netIP = null;
		int netPort = 0;
		Scanner scan = new Scanner(System.in);
		
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
		
		serverSocket = new RxpServerSocket();
		serverSocket.listen(port, 2300);
		socket = serverSocket.accept();
		
		InputStream reader = socket.getInputStream();
		OutputStream writer = socket.getOutputStream();
		
		//Wait for requests from the client
		
		while (true){				
			if (reader.available() > 0){
				byte buffer[]  = new byte[reader.available()];
				reader.read(buffer);
				System.out.println(new String(buffer));
				if(new String(buffer).equals("test")) {
					System.out.println("Test successful, I think.");
				}
				if(new String(buffer).length() > 3) {
					//System.out.println(new String(buffer).substring(0,4));
					if(new String(buffer).substring(0,4).equals("get:")) {
						String fileName = (new String(buffer)).substring(4,(new String(buffer)).length());
						String filePath = System.getProperty("user.dir") + "\\" + fileName;
						byte[] file = getFileBytes(filePath);
						
						writer.write(file);
						
					}
				}
			} else {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
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
			System.err.println("File could not be read!");
			e.printStackTrace();
		}
		return data;
	}
}

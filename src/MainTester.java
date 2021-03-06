import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Scanner;

public class MainTester {
	private static RxpServerSocket serverSocket;
	private static RxpSocket socket;
	
	public static void main(String[] args) throws IOException {
		
		// Test by running
		// netEmu.py 5000 -l 20
		
		if (args.length == 0){
			serverSocket = new RxpServerSocket();
			serverSocket.listen(8001, 2300);
			socket = serverSocket.accept();
			
			InputStream reader = socket.getInputStream();
			
			while (true){				
				if (reader.available() > 0){
					byte buffer[]  = new byte[reader.available()];
					reader.read(buffer);
					System.out.println(new String(buffer));
				}else{
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
			
		}else{
			socket = new RxpSocket();
			socket.connect(new InetSocketAddress("127.0.0.1", 5000), 2300, 8000);
			
			Scanner scanner = new Scanner(System.in);
			OutputStream writer = socket.getOutputStream();
			
			while (true){
				String line = scanner.nextLine();
				if (line.contains("close")) {
					socket.close();
					System.out.println("Finished Gracefully");
					scanner.close();
					return;
				}else if(line.contains("window")){
					socket.setWindowSize(2);
					System.out.println("Changed window size");
				}else{
					writer.write(line.getBytes());
				}				
			}
		}
	}

}

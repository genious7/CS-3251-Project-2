import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Scanner;

public class MainTester {
	private static RxpServerSocket serverSocket;
	private static RxpSocket socket;
	
	public static void main(String[] args) throws IOException {
		
		if (args.length == 0){
			serverSocket = new RxpServerSocket();
			serverSocket.listen(2300, 2300);
			socket = serverSocket.accept();
			
			InputStream reader = socket.getInputStream();
			
			while (true){
				int data;
				if ((data = reader.read()) == -1) try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				else
					System.out.print((char)data);
			}
			
		}else{
			socket = new RxpSocket();
			socket.connect(new InetSocketAddress("127.0.0.1", 2300), 2300);
			
			
			Scanner scanner = new Scanner(System.in);
			OutputStream writer = socket.getOutputStream();
			
			while (true){
				String line = scanner.nextLine();
				writer.write(line.getBytes());
			}
		}
	}

}

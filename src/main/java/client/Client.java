package client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cli.Command;
import cli.Shell;
import util.Config;

//Tom Tucek, 1325775

public class Client implements IClientCli, Runnable {

	private String componentName;
	@SuppressWarnings("unused")
	private Config config;
	private InputStream userRequestStream;
	private PrintStream userResponseStream;

	private ExecutorService threadPool;
	private Shell shell;
	
	private String host;
	private Integer udpPort;
	private Integer tcpPort;
	
	private String activeUserName;
	private Socket activeTcpSocket;
	private String lastReceivedMessage;
	private ServerSocket serverSocket;
	
	private PrintWriter out;
	private BufferedReader in;
	
	// Key = username, Value = IP:port
	private Map<String, String> usersLookupd;
	
	/**
	 * @param componentName
	 *            the name of the component - represented in the prompt
	 * @param config
	 *            the configuration to use
	 * @param userRequestStream
	 *            the input stream to read user input from
	 * @param userResponseStream
	 *            the output stream to write the console output to
	 */
	public Client(String componentName, Config config,
			InputStream userRequestStream, PrintStream userResponseStream) {
		this.componentName = componentName;
		this.config = config;
		this.userRequestStream = userRequestStream;
		this.userResponseStream = userResponseStream;
		
		this.activeUserName = null;
		this.activeTcpSocket = null;
		this.lastReceivedMessage = null;
		this.in = null;
		this.out = null;
		
		this.serverSocket = null;
		
		this.usersLookupd = new TreeMap<String, String>();

		threadPool = Executors.newCachedThreadPool();
		
		host = config.getString("chatserver.host");
		udpPort = Integer.valueOf(config.getString("chatserver.udp.port"));
		tcpPort = Integer.valueOf(config.getString("chatserver.tcp.port"));
				
	}

	@Override
	public void run() {
		shell = new Shell("[" + this.componentName + "]shell", this.userRequestStream, this.userResponseStream);
		shell.register(this);
		threadPool.execute(shell);

		writeToShell("Starting Chatclient. Using Chatserver: " + host + ", Tcp Port: " + tcpPort +", Udp Port: " + udpPort);
	}

	@Command
	@Override
	public String login(String username, String password) throws IOException {

		if(this.activeTcpSocket != null)
		{
			return "Already logged in. Please log out before logging in again.";
		}
		
		Socket socket = null;
		
		try {		
			socket = new Socket(host, tcpPort);
			out = new PrintWriter(socket.getOutputStream(), true);
			in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				
			out.println("!login " + username + " " + password);
			
			String input = in.readLine();

			writeToShell(input);
				
			if(input.equals("Successfully logged in."))
			{
				// Login successful
				this.activeTcpSocket = socket;
				this.activeUserName = username;

				// new listener thread, displaying messages from server
				this.threadPool.execute(new TCPServerListenerThread(in));
				
			}
			else
			{
				// Login failed, closing connection
				if(socket!=null)
					socket.close();
				return null;
			}
			
		} catch (IOException e) {

			writeToShell("ERROR: Connection error to server: " + host + ". Closing connection.");
			
			if(socket!=null)
				socket.close();
		}
		
		return null;
	}

	@Command
	@Override
	public String logout() throws IOException {
		
		if(this.activeTcpSocket == null)
		{
			return "ERROR: Not logged in. Unable to log out.";
			
		}
		
		//String input = null;
		//try
		//{
			synchronized (out) {
				out.println("!logout");
		//		input = in.readLine();
			}
		//}
		//catch(IOException ie)
		//{
		//	writeToShell("ERROR: Connection to Server lost before properly logged out.");
		//}

		if(this.activeTcpSocket != null && !this.activeTcpSocket.isClosed())
			this.activeTcpSocket.close();
			
		this.activeUserName = null;
		this.activeTcpSocket = null;
		this.in = null;
		this.out = null;

		if(serverSocket != null)
		{
			serverSocket.close();
			serverSocket = null;
		}
		
		// not sure if supposed to be reset
		this.lastReceivedMessage = null;	
		this.usersLookupd.clear();
		
		//return input;
		return "Successfully logged out.";
	}

	@Command
	@Override
	public String send(String message) throws IOException {

		if(this.activeTcpSocket == null)
		{
			return "ERROR: Not logged in. Unable to send messages or commands.";
		}
		
		synchronized (out) {
			this.out.println("!send " + message);
		}
		
		return null;
	}

	@Command
	@Override
	public String list() throws IOException {
		threadPool.execute(new UDPThread("!list"));
		
		return null;
	}

	@Command
	@Override
	public String msg(String username, String message) throws IOException {

		if(this.activeTcpSocket == null)
		{
			return "ERROR: Not logged in. Unable to send messages or commands.";
		}
		
		String ipp = null;
		if(usersLookupd.containsKey(username))
		{
			ipp = usersLookupd.get(username);
		}
		else
		{
			ipp = lookup(username);
			
			if(ipp == null)
				return null;
		}

		Socket socket = null;
		
		try {	
			int port = Integer.valueOf(ipp.substring(ipp.lastIndexOf(":") + 1));

			ipp = ipp.substring(0, ipp.lastIndexOf(":"));
			ipp = InetAddress.getByName(ipp).getHostAddress();
			socket = new Socket(ipp, port);
			PrintWriter out2 = new PrintWriter(socket.getOutputStream(), true);
			BufferedReader in2 = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				
			out2.println("[PM]" + activeUserName + ": " + message);
			
			String input = in2.readLine();

			//writeToShell(input);
				
			if(input != null && input.equals("!ack"))
			{
				writeToShell(username + " replied with !ack.");
			}
			else
			{
				writeToShell("Failed to get acknowledgement from client [" + username + "]. Closing connection.");
			}
			
		} catch (IOException e) {

			writeToShell("ERROR: Connection error to client: [" + username + "]. Closing connection.");
			
		}
		
		if(socket != null)
			socket.close();
		
		return null;
	}

	@Command
	@Override
	public String lookup(String username) throws IOException {
		
		if(this.activeTcpSocket == null)
		{
			return "ERROR: Not logged in. Unable to send messages or commands.";
		}

		synchronized (out) {
			this.out.println("!lookup " + username);
		}
		
		int timeout = 3000;

		// not a great solution (have to wait till timeout if not found)
		for(int timer = 0; timer < timeout; timer+=250)
		{
			try {
				Thread.sleep(250);
			} catch (InterruptedException e) {
				// everything's fine
			}
			
			if(usersLookupd.containsKey(username))
				return usersLookupd.get(username);
		}
		
		return null;
	}

	@Command
	@Override
	public String register(String privateAddress) throws IOException {

		if(this.activeTcpSocket == null)
		{
			return "ERROR: Not logged in. Unable to send messages or commands.";
		}
		
		if(privateAddress.lastIndexOf(":") < 0)
		{
			return "ERROR: Illegal format for command !register. Please use <IP:Port>.";
		}
		
		synchronized (out) {
			out.println("!register " + privateAddress);
		}

		if(serverSocket != null)
		{
			serverSocket.close();
			serverSocket = null;
		}
		
		try {
			serverSocket = new ServerSocket(Integer.valueOf(privateAddress.substring(privateAddress.lastIndexOf(":") + 1)));
		} catch (NumberFormatException | IOException e1) {	
			return "Error creating TCP socket: " + e1.getMessage();
		}

		//writeToShell("Opened new TCP Socket, Port: " + privateAddress.substring(privateAddress.lastIndexOf(":") + 1));
		threadPool.execute(new TCPClientListenerThread(serverSocket));
		
		return null;
	}

	@Command
	@Override
	public String lastMsg() throws IOException {

		if(this.lastReceivedMessage == null)
			return "No message received!";
		else
			return this.lastReceivedMessage;
		
	}

	@Command
	@Override
	public String exit() throws IOException {

		if(this.activeTcpSocket != null)
			this.logout();

		if(serverSocket != null)
		{
			serverSocket.close();
			serverSocket = null;
		}
		
		writeToShell("Shutting down Chatclient.");
		
		shell.close();
		
		threadPool.shutdown();
		return null;
	}
	
	// Thread that displays public messages and (non-command-)responses from server
	private class TCPServerListenerThread implements Runnable
	{
		private BufferedReader serverIn;
		
		public TCPServerListenerThread(BufferedReader in)
		{
			this.serverIn = in;
		}
		

		@Override
		public void run() {

			String input;
			
			try {
				
				while(true){
					
					input = serverIn.readLine();
					
					if(input == null)
						break;
														
					if(input.charAt(0) != '!')		// not a really clean solution to filter only user messages (users starting with ! cause problems)
					{
						writeToShell(input);
						lastReceivedMessage = input;
					}
					else if(input.length() > 4 && input.substring(0, 3).equals("!sm"))	// server messages
					{
						writeToShell(input.substring(4));
					}
					else if(input.length() > 14 && input.substring(0, 14).equals("!lookup-result"))
					{
						usersLookupd.put(input.substring(15, input.lastIndexOf(" ")), input.substring(input.lastIndexOf(" ") + 1));
					}
					
				}
				
			} catch (IOException e) {
				
			}

			writeToShell("Connection to server closed.");
			activeTcpSocket = null;
		}
		
	}
	
	// Thread waiting for new TCP connections from clients
	private class TCPClientListenerThread implements Runnable
	{
		ServerSocket socket;
		public TCPClientListenerThread(ServerSocket socket)
		{
			this.socket = socket;
		}
		
		@Override
		public void run() {

			try {
				while(true)
				{
					Socket s = socket.accept();
															
				    try {

						PrintWriter out3;
						BufferedReader in3;
				    	out3 = new PrintWriter(s.getOutputStream(), true);
						in3 = new BufferedReader(new InputStreamReader(s.getInputStream()));
						
					    String input = in3.readLine();
						writeToShell(input);
						
						out3.println("!ack");
						
					} catch (IOException e) {

						writeToShell("ERROR: I/O communication error with client [" 
									+ s.getInetAddress().getHostAddress() + "]. Closing connection.");
					}
				    
				   	s.close();	
				}
				
			}
			catch(IOException e)
			{
				//e.printStackTrace();
				//writeToShell("TCP Client Listener Thread shutting down.");
			}
			
		}
	}
	
	
	// Thread for UDP communication
	private class UDPThread implements Runnable
	{
		private String msg;
		
		public UDPThread(String msg)
		{
			this.msg = msg;
		}

		@Override
		public void run() {
			
			DatagramSocket socket;
			
			try {
				socket = new DatagramSocket();
			} catch (SocketException e1) {

				writeToShell("Error creating new UDP socket. Aborting UDP operation.");
				
				return;	
			}
			
			InetAddress address;
			byte[] buf = msg.getBytes(); //new byte[256];
			
			try {
				 address = InetAddress.getByName(host);
			} catch (UnknownHostException e) {

				writeToShell("Error connecting to host [" + host + "]. Host not found. Aborting UDP operation.");

				socket.close();
				return;				
			}
			
			DatagramPacket packet = new DatagramPacket(buf, buf.length, address, udpPort);
			
			try {
				socket.send(packet);
			} catch (IOException e) {

				writeToShell("Error sending UDP packet to host [" + host + "]. Aborting UDP operation.");

				socket.close();
				return;	
			}
			
			buf = new byte[256];
			packet = new DatagramPacket(buf, buf.length);
			try {
				socket.setSoTimeout(5000);
			} catch (SocketException e1) {

				writeToShell("Error seting UDP socket timeout to 5 seconds.");
			}
			
			try {
				socket.receive(packet);
			}  catch (SocketTimeoutException te) {

				writeToShell("Error receiving UDP packet from host [" + host + "]. "
							+ "Host did not respond in time (5s). Aborting UDP operation.");

				socket.close();
				return;	
			}
			catch (IOException e) {

				writeToShell("Error receiving UDP packet from host [" + host + "]. Aborting UDP operation.");

				socket.close();
				return;	
			}
						
			writeToShell(new String(packet.getData(), 0, packet.getLength()));
			
			socket.close();	
		}
		
	}
	
	
	/**
	 * @param args
	 *            the first argument is the name of the {@link Client} component
	 */
	public static void main(String[] args) {

		String name = "ChatClient";
		if(args.length > 0)
			name = args[0];
		
		Client client = new Client(name, new Config("client"), System.in,
				System.out);
		
		client.run();
	}
	
	private void writeToShell(String s)
	{
		try {
			synchronized (shell) {
				shell.writeLine(s);
			}
		} catch (IOException ie) {
			userResponseStream.println("Error with shell output: " + ie.getMessage() + "\nOriginal message: " + s);
		}
	}

	// --- Commands needed for Lab 2. Please note that you do not have to
	// implement them for the first submission. ---

	@Override
	public String authenticate(String username) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

}

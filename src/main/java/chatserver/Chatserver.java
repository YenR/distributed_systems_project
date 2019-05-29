package chatserver;

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
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import cli.Command;
import cli.Shell;
import util.Config;

// Tom Tucek, 1325775

public class Chatserver implements IChatserverCli, Runnable {

	private String componentName;
	private Config config;
	private Config userConfig;
	private InputStream userRequestStream;
	private PrintStream userResponseStream;
	
	private ServerSocket tcpSocket;
	private DatagramSocket udpSocket;
	private ExecutorService threadPool;
	
	private Shell shell;
	
	private Map<String, Socket> onlineUsers;
	private Map<String, PrintWriter> onlineOutputWriters;
	private Map<String, String> registeredUsers;
	//private Set<Socket> activeSockets;

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
	public Chatserver(String componentName, Config config,
			InputStream userRequestStream, PrintStream userResponseStream) {
		this.componentName = componentName;
		this.config = config;
		this.userRequestStream = userRequestStream;
		this.userResponseStream = userResponseStream;

		threadPool = Executors.newCachedThreadPool();
				
		String tcpPort = config.getString("tcp.port");
		String udpPort = config.getString("udp.port");
		
		userConfig = new Config("user");
		onlineUsers = new TreeMap<String, Socket>();
		onlineOutputWriters = new TreeMap<String, PrintWriter>();
		registeredUsers = new TreeMap<String, String>();
		//activeSockets = new HashSet<Socket>();
			
		try {
			tcpSocket = new ServerSocket(Integer.valueOf(tcpPort));
		} catch (NumberFormatException | IOException e1) {	
			userResponseStream.println("Error creating TCP socket: " + e1.getMessage());
			e1.printStackTrace();
		}
				
		try {
			udpSocket = new DatagramSocket(Integer.valueOf(udpPort));
		} catch (NumberFormatException | SocketException e) {
			userResponseStream.println("Error creating UDP socket: " + e.getMessage());
			e.printStackTrace();
		}
				
	}

	// Thread waiting for new TCP connections
	private class TCPMainListenerThread implements Runnable
	{
		ServerSocket socket;
		public TCPMainListenerThread(ServerSocket socket)
		{
			this.socket = socket;
		}
		
		@Override
		public void run() {

			try {
				while(true)
				{
					Socket s = socket.accept();
					
					//writeToShell("Establishing new tcp connection to " + s.getInetAddress().getHostAddress());
					
					threadPool.execute(new TCPSubListenerThread(s));
				}
				
			}
			catch(IOException e)
			{
				//e.printStackTrace();
				//writeToShell("TCP Listener Thread shutting down.");
			}
			
		}
	}

	// Thread for a single TCP connection
	private class TCPSubListenerThread implements Runnable
	{
		private Socket socket;
		public TCPSubListenerThread(Socket socket)
		{
			this.socket = socket;
			//activeSockets.add(socket);
		}
		
		public void closeSocket()
		{
			//activeSockets.remove(socket);
			try {
				socket.close();
			} catch (IOException e) {

				writeToShell("ERROR: Error closing Socket. " + e.getMessage());
			}
		}
		
		@Override
		public void run() {
			
			PrintWriter out;
			BufferedReader in;
			
		    try {
		    	
		    	out = new PrintWriter(socket.getOutputStream(), true);
				in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
				
			} catch (IOException e) {

				writeToShell("ERROR: Could not establish I/O streams to client [" 
							+ socket.getInetAddress().getHostAddress() + "]. Closing connection.");
				
				this.closeSocket();
				return;
			}
		    
		    String input;
		    
		    try {
				 input = in.readLine();
			} catch (IOException e) {

				writeToShell("ERROR: Could not read message from client [" 
							+ socket.getInetAddress().getHostAddress() + "]. Closing connection.");
				
				this.closeSocket();
				return;
			}

		    //writeToShell("Got TCP message from client[" + socket.getInetAddress().getHostAddress() + "]: " + input);
		    
		    if(input.substring(0, 6).equals("!login"))
		    {
		    	String name = input.substring(7, input.lastIndexOf(" "));
		    	String pw = input.substring(input.lastIndexOf(" ") + 1);
		    	
		    	synchronized (onlineUsers) {
			    	if(onlineUsers.containsKey(name)) {
		    			out.println("Login failed. User \"" + name + "\" already logged in.");
			    		
			    		this.closeSocket();
			    		return;
			    	} 
				}
		    		
	    		if (userConfig.listKeys().contains(name + ".password") 
		    			&& userConfig.getString(name + ".password").equals(pw)) {
		    		
		    		// LOGIN SUCCESSFUL
		    		
		    		//userResponseStream.println("login info: " + name + ", " + pw +" LOGIN SUCCESS");
		    		out.println("Successfully logged in.");
		    		
		    		synchronized (onlineUsers) {
			    		onlineUsers.put(name, socket);
			    		onlineOutputWriters.put(name, out);
			    		//activeSockets.add(socket);
					}
		    		
		    		// Listener Loop, connection to client remains
		    		try {
						while(true)
						{
							input = in.readLine();
							if(input != null)
							{
								if(input.equals("!logout"))							// !logout
								{
									//out.println("Successfully logged out.");
									break;
								}
								else if(input.substring(0, 5).equals("!send"))		// !send <message>
								{
									synchronized (onlineUsers) {
										for(Entry<String, PrintWriter> entry : onlineOutputWriters.entrySet())
										{
											// don't return message to sender
											if(!entry.getKey().equals(name))
											{
												entry.getValue().println(name + ": " + input.substring(6));
											}
										}
									}
								}
								else if(input.substring(0, 9).equals("!register"))	// !register <IP:port>
								{									
									String ip = input.substring(10, input.lastIndexOf(":"));
									boolean fine = true;
									try
									{
										//writeToShell("Got address: " + InetAddress.getByName(ip).getHostAddress());
										InetAddress.getByName(ip);
									}
									catch(UnknownHostException uhe)
									{
										fine = false;
									}
									
									if(fine == false || input.lastIndexOf(":") < 0 || input.substring(input.lastIndexOf(":")).length() < 1)
									{
										out.println("!sm Error: Could not register the given address.");
									}
									else
									{
										registeredUsers.put(name, input.substring(10));
										out.println("!sm Successfully registered address for " + name);
									}
								}
								else if(input.substring(0, 7).equals("!lookup"))	// !lookup <username>
								{
									String nameToFind = input.substring(8);
									if(registeredUsers.containsKey(nameToFind))
										out.println("!lookup-result " + nameToFind + " " + registeredUsers.get(nameToFind));
									else
										out.println("!sm [" + nameToFind + "] not found. Wrong username or user not reachable.");
								}
							}
									
						}
					} catch (IOException e) {
						
					}

		    		//writeToShell("Closing TCP connection to client[" + socket.getInetAddress().getHostAddress() + "].");
		    				    		
		    		// close connection
		    		synchronized (onlineUsers) {
			    		onlineUsers.remove(name);
			    		onlineOutputWriters.remove(name);
					}
		    		this.closeSocket();

		    		    				    		
		    	} else {
		    		//userResponseStream.println("login info: " + name + ", " + pw +" LOGIN FAILED");
		    		out.println("Wrong username or password.");
		    		
		    		this.closeSocket();
		    		return;
		    	}
		    }
		    else
		    {
		    	out.println("Not logged in. Please log in before using commands other than !list.");
	    		
	    		this.closeSocket();
	    		return;
		    }
		}
	}
	
	
	// Thread waiting for new UDP connections
	private class UDPMainListenerThread implements Runnable
	{
		DatagramSocket socket;
		public UDPMainListenerThread(DatagramSocket socket)
		{
			this.socket = socket;
		}

		@Override
		public void run() {

			try {
				while(true)
				{
					byte[] buf = new byte[256];
					DatagramPacket packet = new DatagramPacket(buf, buf.length);
					socket.receive(packet);

					//writeToShell("Establishing new udp connection to " + packet.getAddress().getHostAddress());
					
					threadPool.execute(new UDPSubListenerThread(socket, packet));
				}
				
			}
			catch(Exception e)
			{
				//writeToShell("UDP Listener Thread shutting down.");
			}
			
		}
		
	}
	
	// Thread for a single UDP connection
	private class UDPSubListenerThread implements Runnable
	{
		DatagramSocket socket;
		DatagramPacket packet;
		public UDPSubListenerThread(DatagramSocket socket, DatagramPacket packet)
		{
			this.packet = packet;
			try {
				this.socket = new DatagramSocket();
			} catch (SocketException e) {
				
				writeToShell("ERROR: Could not create new UDP Socket to " + packet.getAddress().getHostAddress());
				
				this.socket = null;
			}
		}

		@Override
		public void run() {
			
			if(this.socket == null)
				return;
			
			String msg = new String(packet.getData(), 0, packet.getLength());
			
			//writeToShell("Received udp packet, message: " + msg);
			
			String response = "";
			
			if(msg.equals("!list"))
			{
				synchronized (onlineUsers) {

					for(String user : onlineUsers.keySet())
					{
						response += "* " + user + "\n";
					}
					
				}
				
				if(response.equals(""))
					response = "No users online.";
			}
			else
			{
				response = "Unknown UDP command.";
			}
			
			byte[] buf = new byte[response.length() + 1];
			buf = response.getBytes();
			
			DatagramPacket outPacket = new DatagramPacket(buf, buf.length, packet.getAddress(), packet.getPort());
			try {
				socket.send(outPacket);
			} catch (IOException e) {
				
				writeToShell("ERROR: Failed to send udp answer to " + packet.getAddress().getHostAddress());
			}		
			
		}
		
	}
	

	@Override
	public void run() {

		shell = new Shell("[" + this.componentName + "]shell", this.userRequestStream, this.userResponseStream);
		shell.register(this);
		threadPool.execute(shell);

		writeToShell("Starting Chatserver. Using Tcp Port: " + config.getString("tcp.port") + 
				", Udp Port: " + config.getString("udp.port"));
		
		// start TCP and UDP listeners for new connections
		threadPool.execute(new TCPMainListenerThread(tcpSocket));
		//writeToShell("Started TCP Main Thread.");
		
		threadPool.execute(new UDPMainListenerThread(udpSocket));
		//writeToShell("Started UDP Main Thread.");
	}

	@Override
	@Command
	public String users() throws IOException {

		//userResponseStream.println("!users called");
		String result = "";
		int counter = 1;
		// Assumes that only one password per user is saved in user.config
				
		// use TreeSet for alphabetical sorting
		Set<String> users = new TreeSet<String>(userConfig.listKeys());
		for(String user : users)
		{
			String username = user.substring(0, user.lastIndexOf("."));
			result += counter++ + ". " + username;
			synchronized (onlineUsers) {
				if(onlineUsers.containsKey(username))
					result += "  online\n";
				else
					result += "  offline\n";
			}
		}
		return result;
	}

	@Override
	@Command
	public String exit() throws IOException {
		
		writeToShell("Shutting down Chatserver.");
		
		shell.close();
		udpSocket.close();
		tcpSocket.close();
		
		synchronized (onlineUsers) {
			for(Socket s : onlineUsers.values()) //activeSockets)
			{
				s.close();
			}
			
			threadPool.shutdown();
		}
		
		//threadPool.shutdownNow();		
		
		/*for(Thread t : Thread.getAllStackTraces().keySet())
		{
			userResponseStream.println("Thread running: " + t.getName());
		}*/
		
		return null;
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
	
	/**
	 * @param args
	 *            the first argument is the name of the {@link Chatserver}
	 *            component
	 */
	public static void main(String[] args) {
		
		String name = "ChatServer";
		if(args.length > 0)
			name = args[0];
		
		Chatserver chatserver = new Chatserver(name,
				new Config("chatserver"), System.in, System.out);
				
		chatserver.run();
		
	}

}

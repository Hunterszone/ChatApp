package chat_engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
/**
 *
 * This is multi-threaded chat server. It accepts
 * multiple clients simultaneously and serves them. Clients are
 * able to send messages to the server. When a client sends
 * a message to the server, the message is dispatched to all
 * the clients connected to the server.
 *
 * The server consists of two components - "server core" and
 * "client handlers".
 *
 * The "server core" consists of two threads:
 * - ChatServer - accepts client connections, creates
 * client threads to handle them and starts these threads
 * - ServerDispatcher - waits for messages and when some
 * message arrive sends it to all the clients connected to
 * the server
 *
 * The "client handlers" consist also of two threads:
 * - ClientListener - listens for message arrivals from the
 * socket and forwards them to the ServerDispatcher thread 

 * - ClientSender - sends messages to the client
 *
 * For each accepted client, a ClientListener and ClientSender
 * threads are created and started. A Client object is also
 * created to maintain the information about the client and is
 * added to the ServerDispatcher's clients list. When some
 * client is disconnected, is it removed from the clients list
 * and both its ClientListener and ClientSender threads are
 * interrupted.
 */
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Vector;

/**
 * ChatServer class is the entry point for the server. It opens a server socket,
 * starts the dispatcher thread and infinitely accepts client connections,
 * creates threads for handling them and starts these threads.
 */
public class ChatServer {
	public static final int LISTENING_PORT = 2002;
	public static String KEEP_ALIVE_MESSAGE = "!keep-alive";
	public static int CLIENT_READ_TIMEOUT = 5 * 60 * 1000;
	private static ServerSocket mServerSocket;

	private static ServerDispatcher mServerDispatcher;

	public static void main(String[] args) {
		// Start listening on the server socket
		bindServerSocket();

		// Start the ServerDispatcher thread
		mServerDispatcher = new ServerDispatcher();
		mServerDispatcher.start();

		// Infinitely accept and handle client connections
		handleClientConnections();
	}

	private static void bindServerSocket() {
		try {
			mServerSocket = new ServerSocket(LISTENING_PORT);
			System.out.println("ChatServer started on " + "port " + LISTENING_PORT);
		} catch (IOException ioe) {
			System.err.println("Can not start listening on " + "port " + LISTENING_PORT);
			ioe.printStackTrace();
			System.exit(-1);
		}

	}

	private static void handleClientConnections() {
		while (true) {
			try {
				Socket socket = mServerSocket.accept();
				Client client = new Client();
				client.mSocket = socket;
				ClientListener clientListener = new ClientListener(client, mServerDispatcher);
				ClientSender clientSender = new ClientSender(client, mServerDispatcher);
				client.mClientListener = clientListener;
				clientListener.start();
				client.mClientSender = clientSender;
				clientSender.start();
				mServerDispatcher.addClient(client);
			} catch (IOException ioe) {
				ioe.printStackTrace();
			}
		}
	}
}

/**
 * ServerDispatcher class is purposed to listen for messages received from the
 * clients and to dispatch them to all the clients connected to the chat server.
 */
class ServerDispatcher extends Thread {
	private Vector<String> mMessageQueue = new Vector<>();
	private Vector<Client> mClients = new Vector<>();

	/**
	 * Adds given client to the server's client list.
	 */
	public synchronized void addClient(Client aClient) {
		mClients.add(aClient);
	}

	/**
	 * Deletes given client from the server's client list if the client is in the
	 * list.
	 */
	public synchronized void deleteClient(Client aClient) {
		int clientIndex = mClients.indexOf(aClient);
		if (clientIndex != -1)
			mClients.removeElementAt(clientIndex);
	}

	/**
	 * 
	 * Adds given message to the dispatcher's message queue and notifies this thread
	 * to wake up the message queue reader (getNextMessageFromQueue method).
	 * dispatchMessage method is called by other threads (ClientListener) when a
	 * message is arrived.
	 */
	public synchronized void dispatchMessage(Client aClient, String aMessage) {
		Socket socket = aClient.mSocket;
		String senderIP = socket.getInetAddress().getHostAddress();
		String senderPort = "" + socket.getPort();
		aMessage = senderIP + ":" + senderPort + " : " + aMessage;
		mMessageQueue.add(aMessage);
		notify();
	}

	/**
	 * @return and deletes the next message from the message queue. If there is no
	 *         messages in the queue, falls in sleep until notified by
	 *         dispatchMessage method.
	 */
	private synchronized String getNextMessageFromQueue() throws InterruptedException {
		while (mMessageQueue.size() == 0)
			wait();
		String message = (String) mMessageQueue.get(0);
		mMessageQueue.removeElementAt(0);
		return message;
	}

	/**
	 * Sends given message to all clients in the client list. Actually the message
	 * is added to the client sender thread's message queue and this client sender
	 * thread is notified to process it.
	 */
	private void sendMessageToAllClients(String aMessage) {
		for (int i = 0; i < mClients.size(); i++) {
			Client client = (Client) mClients.get(i);
			client.mClientSender.sendMessage(aMessage);
		}
	}

	public void run() {
		try {
			while (true) {
				String message = getNextMessageFromQueue();
				sendMessageToAllClients(message);
			}
		} catch (InterruptedException ie) {
			// Thread interrupted. Stop its execution
		}
	}
}

/**
 * Client class contains information about a client, connected to the server.
 */
class Client {
	public Socket mSocket = null;
	public ClientListener mClientListener = null;
	public ClientSender mClientSender = null;
}

/**
 * ClientListener class listens for client messages and forwards them to
 * ServerDispatcher.
 */
class ClientListener extends Thread {
	private ServerDispatcher mServerDispatcher;
	private Client mClient;
	private BufferedReader mSocketReader;

	public ClientListener(Client aClient, ServerDispatcher aSrvDispatcher) throws IOException {
		mClient = aClient;
		mServerDispatcher = aSrvDispatcher;
		Socket socket = aClient.mSocket;
		socket.setSoTimeout(ChatServer.CLIENT_READ_TIMEOUT);
		mSocketReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
	}

	/**
	 * Until interrupted, reads messages from the client socket, forwards them to
	 * the server dispatcher's queue and notifies the server dispatcher.
	 */
	public void run() {
		try {
			while (!isInterrupted()) {
				try {
					String message = mSocketReader.readLine();

					if (message == null)
						break;
					mServerDispatcher.dispatchMessage(mClient, message);
				} catch (SocketTimeoutException ste) {
					mClient.mClientSender.sendKeepAlive();
				}
			}
		} catch (IOException ioex) {
			// Problem reading from socket (broken connection)
		}

		// Communication is broken. Interrupt both listener and
		// sender threads
		mClient.mClientSender.interrupt();
		mServerDispatcher.deleteClient(mClient);
	}
}

/**
 * Sends messages to the client. Messages waiting to be sent are stored in a
 * message queue. When the queue is empty, ClientSender falls in sleep until a
 * new message is arrived in the queue. When the queue is not empty,
 * ClientSender sends the messages from the queue to the client socket.
 */
class ClientSender extends Thread {
	private Vector<String> mMessageQueue = new Vector<>();

	private ServerDispatcher mServerDispatcher;
	private Client mClient;
	private PrintWriter mOut;

	public ClientSender(Client aClient, ServerDispatcher aServerDispatcher) throws IOException {
		mClient = aClient;
		mServerDispatcher = aServerDispatcher;
		Socket socket = aClient.mSocket;
		mOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()));
	}

	/**
	 * Adds given message to the message queue and notifies this thread (actually
	 * getNextMessageFromQueue method) that a message is arrived. sendMessage is
	 * always called by other threads (ServerDispatcher).
	 */
	public synchronized void sendMessage(String aMessage) {
		mMessageQueue.add(aMessage);
		notify();
	}

	/**
	 * Sends a keep-alive message to the client to check if it is still alive. This
	 * method is called when the client is inactive too long to prevent serving dead
	 * clients.
	 */
	public void sendKeepAlive() {
		sendMessage(ChatServer.KEEP_ALIVE_MESSAGE);
	}

	/**
	 * @return and deletes the next message from the message queue. If the queue is
	 *         empty, falls in sleep until notified for message arrival by
	 *         sendMessage method.
	 */
	private synchronized String getNextMessageFromQueue() throws InterruptedException {
		while (mMessageQueue.size() == 0)
			wait();
		String message = (String) mMessageQueue.get(0);
		mMessageQueue.removeElementAt(0);
		return message;
	}

	/**
	 * Sends given message to the client's socket.
	 */
	private void sendMessageToClient(String aMessage) {
		mOut.println(aMessage);
		mOut.flush();
	}

	/**
	 * Until interrupted, reads messages from the message queue and sends them to
	 * the client's socket.
	 */
	public void run() {
		try {
			while (!isInterrupted()) {
				String message = getNextMessageFromQueue();
				sendMessageToClient(message);
			}
		} catch (Exception e) {
			// Communication problem
		}

		// Communication is broken. Interrupt both listener
		// and sender threads
		mClient.mClientListener.interrupt();
		mServerDispatcher.deleteClient(mClient);
	}
}
package me.tagavari.airmessageserver.connection.connect;

import me.tagavari.airmessageserver.server.Main;
import me.tagavari.airmessageserver.server.PreferencesManager;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

class ConnectWebSocketClient extends WebSocketClient {
	//Creating the constants
	//private static final URI connectHostname = URI.create("wss://connect.airmessage.org");
	private static final URI connectHostname = URI.create("ws://localhost:1259");
	private static final int connectTimeout = 8 * 1000; //8 seconds
	
	//Creating the callbacks
	private final ConnectionListener connectionListener;
	
	static ConnectWebSocketClient createInstanceRegister(String idToken, ConnectionListener connectionListener) {
		Map<String, String> headers = new HashMap<>();
		headers.put("Cookie", new CookieBuilder()
				.with("communications", NHT.commVer)
				.with("isServer", true)
				.with("installationID", PreferencesManager.getInstallationID())
				.with("idToken", idToken)
				.toString()
		);
		
		return new ConnectWebSocketClient(connectHostname, headers, connectTimeout, connectionListener);
	}
	
	static ConnectWebSocketClient createInstanceExisting(String userID, ConnectionListener connectionListener) {
		Map<String, String> headers = new HashMap<>();
		headers.put("Cookie", new CookieBuilder()
				.with("communications", NHT.commVer)
				.with("isServer", true)
				.with("installationID", PreferencesManager.getInstallationID())
				.with("userID", userID)
				.toString()
		);
		
		return new ConnectWebSocketClient(connectHostname, headers, connectTimeout, connectionListener);
	}
	
	public ConnectWebSocketClient(URI serverUri, Map<String, String> httpHeaders, int connectTimeout, ConnectionListener connectionListener) {
		super(serverUri, new Draft_6455(), httpHeaders, connectTimeout);
		
		this.connectionListener = connectionListener;
	}
	
	@Override
	public void onOpen(ServerHandshake handshakeData) {
		Main.getLogger().log(Level.INFO, "Connection to Connect relay opened");
		connectionListener.handleConnect();
	}
	
	@Override
	public void onMessage(ByteBuffer bytes) {
		connectionListener.processData(bytes);
	}
	
	@Override
	public void onMessage(String message) {
	
	}
	
	@Override
	public void onClose(int code, String reason, boolean remote) {
		Main.getLogger().log(Level.INFO,  "Connection to Connect relay lost: " + code + " / " + reason);
		connectionListener.handleDisconnect(code, reason);
	}
	
	@Override
	public void onError(Exception exception) {
		Main.getLogger().log(Level.WARNING, exception.getMessage(), exception);
	}
}
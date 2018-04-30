package me.tagavari.airmessage.server;

import com.github.rodionmoiseev.c10n.C10N;
import com.github.rodionmoiseev.c10n.annotations.DefaultC10NAnnotations;
import io.sentry.Sentry;
import io.sentry.context.Context;
import io.sentry.event.UserBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.security.Security;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.*;

class Main {
	//Creating the reference values
	static final boolean MODE_DEBUG = true;
	static final String PREFIX_DEBUG = "DEBUG LOG: ";
	static final int serverStateStarting = 0;
	static final int serverStateRunning = 1;
	static final int serverStateFailedDatabase = 2;
	static final int serverStateFailedServer = 3;
	
	private static final File logFile = new File(Constants.applicationSupportDir, "logs/latest.log");
	
	//Creating the variables
	private static TimeHelper timeHelper;
	private static Logger logger;
	
	private static int serverState = serverStateStarting;
	
	public static void main(String[] args) throws IOException {
		//Configuring the logger
		logger = Logger.getGlobal();
		logger.setLevel(Level.FINEST);
		if(!logFile.getParentFile().exists()) logFile.getParentFile().mkdir();
		else if(logFile.exists()) Files.move(logFile.toPath(), Constants.findFreeFile(logFile.getParentFile(), new SimpleDateFormat("YYYY-MM-dd").format(new Date()) + ".log", "-", 1).toPath());
		
		for(Handler handler : logger.getParent().getHandlers()) logger.getParent().removeHandler(handler);
		
		{
			FileHandler handler = new FileHandler(logFile.getPath());
			handler.setLevel(Level.FINEST);
			handler.setFormatter(getLoggerFormatter());
			logger.addHandler(handler);
		}
		
		{
			ConsoleHandler handler = new ConsoleHandler();
			handler.setLevel(Level.FINEST);
			handler.setFormatter(getLoggerFormatter());
			logger.addHandler(handler);
		}
		
		if(MODE_DEBUG) {
			getLogger().log(Level.INFO, "Server running in debug mode");
		} else {
			//Initializing Sentry
			Sentry.init(Constants.SENTRY_DSN + "?release=" + Constants.SERVER_VERSION);
			Context context = Sentry.getContext();
			
			//Marking the user's ID (with their MAC address)
			String macAddress = Constants.getMACAddress();
			if(macAddress != null) context.setUser(new UserBuilder().setId(macAddress).build());
			
			//Recording the system version
			context.addTag("system_version", System.getProperty("os.version"));
		}
		
		//Preparing the support directory
		if(!Constants.prepareSupportDir()) return;
		
		//Configuring the internationalization engine
		C10N.configure(new DefaultC10NAnnotations());
		
		//Returning if the system is not valid
		if(!runSystemCheck()) return;
		
		//Registering BouncyCastle as a security provider
		Security.addProvider(new BouncyCastleProvider());
		
		//Preparing the preferences
		if(!PreferencesManager.loadPreferences()) return;
		
		//Opening the intro window
		if(PreferencesManager.checkFirstRun()) UIHelper.openIntroWindow();
		
		//Setting up the system tray
		SystemTrayManager.setupSystemTray();
		
		//Processing the arguments
		//processArgs(args);
		
		//Getting the time system
		timeHelper = TimeHelper.getCorrectTimeSystem();
		getLogger().info("Using time system " + Main.getTimeHelper().toString() + " with current time " + System.currentTimeMillis() + " -> " + Main.getTimeHelper().toDatabaseTime(System.currentTimeMillis()));
		
		//Hiding JOOQ's splash
		System.setProperty("org.jooq.no-logo", "true");
		
		//Logging the startup messages
		//getLogger().info("Thank you for using jOOQ " + org.jooq.Constants.FULL_VERSION);
		getLogger().info("Starting AirMessage server version " + Constants.SERVER_VERSION);
		
		//Starting the server
		startServer();
		
		//Starting the update checker
		if(PreferencesManager.getAutoCheckUpdates()) UpdateManager.startUpdateChecker();
		
		//Adding a shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			//Stopping the services
			NetServerManager.destroyServer();
			DatabaseManager.stop();
			UpdateManager.stopUpdateChecker();
			
			//Deleting the uploads directory
			Constants.recursiveDelete(Constants.uploadDir);
		}));
		
		//Starting the event loop
		UIHelper.startEventLoop();
	}
	
	static void startServer() {
		//Updating the server state
		setServerState(serverStateStarting);
		SystemTrayManager.updateStatusMessage();
		
		//Creating the result variable
		boolean result;
		
		//Loading the credentials
		//result = SecurityManager.loadCredentials();
		//if(!result) System.exit(1);
		
		//Starting the database scanner
		result = DatabaseManager.start((int) (PreferencesManager.getScanFrequency() * 1000));
		if(!result) {
			//Updating the server state
			setServerState(serverStateFailedDatabase);
			SystemTrayManager.updateStatusMessage();
			
			//Returning
			return;
		}
		
		//Starting the web socket manager
		result = NetServerManager.createServer(PreferencesManager.getServerPort(), false);
		if(!result) {
			//Updating the server state
			setServerState(serverStateFailedServer);
			SystemTrayManager.updateStatusMessage();
			
			//Returning
			return;
		}
		
		//Updating the server state
		setServerState(serverStateRunning);
		SystemTrayManager.updateStatusMessage();
		
		//Logging a message
		getLogger().info("Initialization complete");
	}
	
	static void restartServer() {
		//Updating the server state
		setServerState(serverStateStarting);
		SystemTrayManager.updateStatusMessage();
		
		//Starting the web socket manager
		boolean result = NetServerManager.createServer(PreferencesManager.getServerPort(), true);
		if(!result) {
			//Updating the server state
			setServerState(serverStateFailedServer);
			SystemTrayManager.updateStatusMessage();
			
			//Returning
			return;
		}
		
		//Updating the server state
		setServerState(serverStateRunning);
		SystemTrayManager.updateStatusMessage();
		
		//Logging a message
		getLogger().info("Restart complete");
	}
	
	private static boolean runSystemCheck() {
		//Setting the default locale
		//JOptionPane.setDefaultLocale(Locale.getDefault()); //TODO check if necessary
		
		//Checking if the system is not macOS
		{
			String systemName = System.getProperty("os.name");
			if(!systemName.toLowerCase().contains("mac")) {
				//Showing a dialog
				JOptionPane.showMessageDialog(null, I18N.i.warning_osIncompatible(systemName), null, JOptionPane.PLAIN_MESSAGE);
				
				//Returning false
				return false;
			}
		}
		
		//Checking if the system version is below OS X yosemite
		if(Constants.compareVersions(Constants.getSystemVersion(), Constants.macOSYosemiteVersion) < 1) {
			//Showing an alert dialog
			return UIHelper.displayVersionWarning();
		}
		
		//Returning true
		return true;
	}
	
	/* private static void processArgs(String[] args) {
		//Iterating over the arguments
		for(String argument : args) {
			//Debug
			if(argument.equals("-debug")) getLogger().setLevel(Level.FINEST);
		}
	} */
	
	private static Formatter getLoggerFormatter() {
		return new Formatter() {
			private final DateFormat dateFormat = new SimpleDateFormat("dd/MM/yy HH:mm:ss");
			
			@Override
			public String format(LogRecord record) {
				String stackTrace = "";
				if(record.getThrown() != null) {
					StringWriter errors = new StringWriter();
					record.getThrown().printStackTrace(new PrintWriter(errors));
					stackTrace = errors.toString();
				}
				return dateFormat.format(record.getMillis()) + ' ' + '[' + record.getLevel().toString() + ']' + ' ' + formatMessage(record) + '\n' + stackTrace;
			}
		};
	}
	
	static TimeHelper getTimeHelper() {
		return timeHelper;
	}
	
	static Logger getLogger() {
		return logger;
	}
	
	static int getServerState() {
		return serverState;
	}
	
	static void setServerState(int value) {
		serverState = value;
	}
}
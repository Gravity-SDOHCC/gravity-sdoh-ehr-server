package ca.uhn.fhir.jpa.starter.gravity;

import java.io.IOException;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Class to create an instance of a Logger that can be used by
 * the server to write logs into files.
 */
public class ServerLogger {

  private static String LOGFILE = "server.log"; // name of the log file

  private ServerLogger() { // Private constructor, throwing an IllegalStateException when it's called by
                           // mistake
    throw new IllegalStateException("Utility class");
  }

  // Get the logger instance
  public static Logger getLogger() {
    return LoggerHolder.INSTANCE;
  }

  private static class LoggerHolder {
    private static final Logger INSTANCE = initLogger(); // Instance of the Logger created when calling initLogger()

    private static Logger initLogger() {
      Logger logger = Logger.getLogger("Server");

      try {
        FileHandler fh = new FileHandler(LOGFILE);
        SimpleFormatter formatter = new SimpleFormatter();
        fh.setFormatter(formatter); // Apply the formatter to the handler
        logger.addHandler(fh); // Add the file handler to the Logger
        logger.setLevel(Level.FINEST);

      } catch (SecurityException e) {
        logger.log(Level.SEVERE,
            "ServerLogger::ServerLogger:SecurityException(SecurityException creating file handler. Logging will not go to file)",
            e);
      } catch (IOException e) {
        logger.log(Level.SEVERE, "ServerLogger::ServerLogger:IOException", e);
      }

      return logger;
    }
  }

  public static String getLogPath() {
    return LOGFILE;
  }

}

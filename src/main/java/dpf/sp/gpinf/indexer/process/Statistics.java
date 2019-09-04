package dpf.sp.gpinf.indexer.process;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.Date;
import java.util.Map.Entry;

import javax.swing.JOptionPane;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dpf.sp.gpinf.indexer.Configuration;
import dpf.sp.gpinf.indexer.Messages;
import dpf.sp.gpinf.indexer.desktop.App;
import dpf.sp.gpinf.indexer.parsers.IndexerDefaultParser;
import dpf.sp.gpinf.indexer.process.task.BaseCarveTask;
import dpf.sp.gpinf.indexer.process.task.ExportFileTask;
import dpf.sp.gpinf.indexer.process.task.ParsingTask;
import dpf.sp.gpinf.indexer.process.task.regex.RegexTask;
import gpinf.dev.data.CaseData;

/**
 * Classe que armazena estatísticas diversas, como número de itens processados, volume processado,
 * número de timeouts, duplicados ignorados, etc. Contém métodos para enviar as estatísticas para
 * arquivo de log.
 */
public class Statistics {

  private static Logger LOGGER = LoggerFactory.getLogger(Statistics.class);
  private static Statistics instance = null;
  
  private static final float IO_ERROR_RATE_TO_WARN = 0.05f;

  CaseData caseData;
  File indexDir;

  //EstatÃ­sticas
  Date start = new Date();
  int splits = 0;
  int timeouts = 0;
  int processed = 0;
  int activeProcessed = 0;
  long volumeIndexed = 0;
  int lastId = -1;
  int corruptCarveIgnored = 0;
  int ignored = 0;
  int previousIndexedFiles = 0;
  int ioerrors = 0;

  public static Statistics get(CaseData caseData, File indexDir) {
    if (instance == null) {
      instance = new Statistics(caseData, indexDir);
    }
    return instance;
  }

  public static Statistics get() {
    return instance;
  }

  private Statistics(CaseData caseData, File indexDir) {
    this.caseData = caseData;
    this.indexDir = indexDir;
  }

  synchronized public int getSplits() {
    return splits;
  }

  synchronized public void incSplits() {
    splits++;
  }

  synchronized public int getTimeouts() {
    return timeouts;
  }

  synchronized public void incTimeouts() {
    timeouts++;
  }

  synchronized public void incProcessed() {
    processed++;
  }

  synchronized public int getProcessed() {
    return processed;
  }

  synchronized public void incIoErrors() {
    ioerrors++;
  }

  synchronized public int getIoErrors() {
    return ioerrors;
  }

  synchronized public void incActiveProcessed() {
    activeProcessed++;
  }

  synchronized public int getActiveProcessed() {
    return activeProcessed;
  }

  synchronized public void addVolume(long volume) {
    volumeIndexed += volume;
  }

  synchronized public long getVolume() {
    return volumeIndexed;
  }

  synchronized public int getCorruptCarveIgnored() {
    return corruptCarveIgnored;
  }

  synchronized public void incCorruptCarveIgnored() {
    corruptCarveIgnored++;
  }

  synchronized public int getIgnored() {
    return ignored;
  }

  synchronized public void incIgnored() {
    ignored++;
  }

  synchronized public void updateLastId(int id) {
    if (id > lastId) {
      lastId = id;
    }
  }

  synchronized public int getLastId() {
    return lastId;
  }

  synchronized public void setLastId(int id) {
    lastId = id;
  }

  public void logarEstatisticas(Manager manager) throws Exception {

    int processed = getProcessed();
    int extracted = ExportFileTask.getItensExtracted();
    int activeFiles = getActiveProcessed();
    int carvedIgnored = getCorruptCarveIgnored();
    int ignored = getIgnored();

    long totalTime = 0;
    Worker[] workers = manager.getWorkers();
    long[] taskTimes = new long[workers[0].tasks.size()];
    for (Worker worker : workers) {
      for (int i = 0; i < taskTimes.length; i++) {
        taskTimes[i] += worker.tasks.get(i).getTaskTime();
        totalTime += worker.tasks.get(i).getTaskTime();
      }
    }
    totalTime = totalTime / (1000000 * Configuration.numThreads);
    for (int i = 0; i < taskTimes.length; i++) {
      long sec = taskTimes[i] / (1000000 * Configuration.numThreads);
      LOGGER.info(workers[0].tasks.get(i).getName() + ":\tProcessing Time:\t" + sec + "s (" + Math.round((100f * sec) / totalTime) + "%)"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    LOGGER.info("File Splits: {}", getSplits()); //$NON-NLS-1$
    LOGGER.info("Timeouts: {}", getTimeouts()); //$NON-NLS-1$
    LOGGER.info("Parsing Exceptions: {}", IndexerDefaultParser.parsingErrors); //$NON-NLS-1$
    LOGGER.info("I/O read errors: {}", this.getIoErrors()); //$NON-NLS-1$
    LOGGER.info("Subitems Found: {}", ParsingTask.getSubitensDiscovered()); //$NON-NLS-1$
    LOGGER.info("Exported Items: {}", extracted); //$NON-NLS-1$
    LOGGER.info("Total Carved Items: {}", BaseCarveTask.getItensCarved()); //$NON-NLS-1$
    LOGGER.info("Carved Ignored (corrupted): {}", carvedIgnored); //$NON-NLS-1$
    LOGGER.info("Ignored Items: {}", ignored); //$NON-NLS-1$

    if (caseData.getAlternativeFiles() > 0) {
      LOGGER.info("Processed {} item previews instead of original ones.", caseData.getAlternativeFiles()); //$NON-NLS-1$
    }

    IndexReader reader = DirectoryReader.open(FSDirectory.open(indexDir));
    int indexed = reader.numDocs() - getSplits() - previousIndexedFiles;
    reader.close();

    if (indexed != processed && (ExportFileTask.hasCategoryToExtract() || RegexTask.isExtractByKeywordsOn())) {
      LOGGER.info("Total Indexed: {}", indexed); //$NON-NLS-1$
    }

    long processedVolume = getVolume() / (1024 * 1024);

    if (activeFiles != processed) {
      LOGGER.info("Active Items: {}", activeFiles); //$NON-NLS-1$
    }

    LOGGER.info("Total processed: {} items in {} seconds ({} MB)", processed, ((new Date()).getTime() - start.getTime()) / 1000, processedVolume); //$NON-NLS-1$

    int discovered = caseData.getDiscoveredEvidences();
    if (processed != discovered) {
      LOGGER.error("Alert: Processed " + processed + " items of" + discovered); //$NON-NLS-1$ //$NON-NLS-2$
    }

    if (!(ExportFileTask.hasCategoryToExtract() || RegexTask.isExtractByKeywordsOn())) {
      if (indexed != discovered - carvedIgnored - ignored) {
        LOGGER.error("Alert: Indexed " + indexed + " items of " + (discovered - carvedIgnored - ignored)); //$NON-NLS-1$ //$NON-NLS-2$
      }
    }/*else 
     if (indexed != extracted)
     throw new Exception("Indexados " + indexed + " itens de " + extracted);
     */
    
    if(this.getIoErrors() > activeFiles * IO_ERROR_RATE_TO_WARN)
    	LOGGER.error("Alert: Errors while reading " + getIoErrors() + " items! Maybe the datasource was unavailable!"); //$NON-NLS-1$ //$NON-NLS-2$
  }

  public void printSystemInfo() throws Exception {
    LOGGER.info("Operating System: {}", System.getProperty("os.name")); //$NON-NLS-1$ //$NON-NLS-2$
    LOGGER.info("Java Version: {}", System.getProperty("java.version")); //$NON-NLS-1$ //$NON-NLS-2$
    LOGGER.info("Architecture: {}", System.getProperty("os.arch")); //$NON-NLS-1$ //$NON-NLS-2$
    LOGGER.info("Current Directory: {}", System.getProperty("user.dir")); //$NON-NLS-1$ //$NON-NLS-2$
    LOGGER.info("CPU Cores: {}", Runtime.getRuntime().availableProcessors()); //$NON-NLS-1$
    LOGGER.info("numThreads: {}", Configuration.numThreads); //$NON-NLS-1$

    long maxMemory = Runtime.getRuntime().maxMemory() / 1000000;
    LOGGER.info("Memory (Heap) Available: {} MB", maxMemory); //$NON-NLS-1$

    StringBuilder args = new StringBuilder();
    RuntimeMXBean bean = ManagementFactory.getRuntimeMXBean();
    for (String arg : bean.getInputArguments()) {
      args.append(arg + " "); //$NON-NLS-1$
    }
    LOGGER.info("Arguments: {}{}", args.toString(), System.getProperty("sun.java.command")); //$NON-NLS-1$ //$NON-NLS-2$

    StringBuilder options = new StringBuilder();
    options.append("Config Options: "); //$NON-NLS-1$
    for (Entry<Object, Object> entry : Configuration.properties.entrySet()) {
      options.append(entry.getKey() + "=" + entry.getValue() + " | "); //$NON-NLS-1$ //$NON-NLS-2$
    }
    LOGGER.info(options.toString());

    int minMemPerThread = 200;
    if (maxMemory / Configuration.numThreads < minMemPerThread) {
      String memoryAlert = Messages.getString("Statistics.LowMemory.1") + minMemPerThread + Messages.getString("Statistics.LowMemory.2") + Messages.getString("Statistics.LowMemory.3") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
          + Messages.getString("Statistics.LowMemory.4") + Messages.getString("Statistics.LowMemory.5") + Messages.getString("Statistics.LowMemory.6"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
      JOptionPane.showMessageDialog(App.get(), memoryAlert, Messages.getString("Statistics.LowMemory.Title"), JOptionPane.WARNING_MESSAGE); //$NON-NLS-1$
      throw new Exception(memoryAlert);
    }

  }

}
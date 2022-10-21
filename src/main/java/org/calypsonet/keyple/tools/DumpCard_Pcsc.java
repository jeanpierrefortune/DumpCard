/* **************************************************************************************
 * Copyright (c) 2018 Calypso Networks Association https://calypsonet.org/
 *
 * See the NOTICE file(s) distributed with this work for additional information
 * regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the terms of the
 * Eclipse Public License 2.0 which is available at http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 ************************************************************************************** */
package org.calypsonet.keyple.tools;

import java.util.*;
import org.calypsonet.terminal.calypso.GetDataTag;
import org.calypsonet.terminal.calypso.SelectFileControl;
import org.calypsonet.terminal.calypso.WriteAccessLevel;
import org.calypsonet.terminal.calypso.card.*;
import org.calypsonet.terminal.calypso.transaction.CardTransactionManager;
import org.calypsonet.terminal.calypso.transaction.SelectFileException;
import org.calypsonet.terminal.reader.CardReader;
import org.calypsonet.terminal.reader.selection.CardSelectionManager;
import org.calypsonet.terminal.reader.selection.CardSelectionResult;
import org.eclipse.keyple.card.calypso.CalypsoExtensionService;
import org.eclipse.keyple.core.service.Plugin;
import org.eclipse.keyple.core.service.SmartCardService;
import org.eclipse.keyple.core.service.SmartCardServiceProvider;
import org.eclipse.keyple.core.util.HexUtil;
import org.eclipse.keyple.plugin.pcsc.PcscPluginFactoryBuilder;
import org.eclipse.keyple.plugin.pcsc.PcscReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dumps the content of card.
 *
 * @since 2.0.0
 */
public class DumpCard_Pcsc {
  private static final Logger logger = LoggerFactory.getLogger(DumpCard_Pcsc.class);
  private static final String CARD_READER_NAME = "ASK LoGO 0";
  private static final String AID = "315449432E"; // "A000000404"; //"315449432E"; //"A000000404";

  private static final SmartCardService smartCardService = SmartCardServiceProvider.getService();
  private static final CalypsoExtensionService calypsoCardService =
      CalypsoExtensionService.getInstance();
  private static CardReader cardReader;
  private static CardTransactionManager cardTransactionManager;

  public static void main(String[] args) {

    Plugin plugin = smartCardService.registerPlugin(PcscPluginFactoryBuilder.builder().build());
    smartCardService.checkCardExtension(calypsoCardService);
    cardReader = plugin.getReader(CARD_READER_NAME);
    plugin
        .getReaderExtension(PcscReader.class, CARD_READER_NAME)
        .setContactless(true)
        .setIsoProtocol(PcscReader.IsoProtocol.T1)
        .setSharingMode(PcscReader.SharingMode.SHARED);

    if (!cardReader.isCardPresent()) {
      throw new IllegalStateException("No card is present in the reader.");
    }

    ApplicationData applicationData;

    logger.info("Dumping the file system of all applications whose AID starts with {}...", AID);

    applicationData = getApplicationData(CalypsoCardSelection.FileOccurrence.FIRST, AID);

    if (applicationData == null) {
      System.out.println("No matching card found.");
    }

    while (applicationData != null) {
      displayApplicationData(applicationData);
      applicationData = getApplicationData(CalypsoCardSelection.FileOccurrence.NEXT, AID);
    }

    logger.info("End of processing.");

    System.exit(0);
  }

  /**
   * Gets the data of the application specified by its AID.
   *
   * @param fileOccurrence First or Next application.
   * @param aid A hex string containing the application identifier
   */
  private static ApplicationData getApplicationData(
      CalypsoCardSelection.FileOccurrence fileOccurrence, String aid) {
    ApplicationData applicationData = new ApplicationData();
    CardSelectionManager cardSelectionManager = smartCardService.createCardSelectionManager();
    cardSelectionManager.prepareSelection(
        calypsoCardService
            .createCardSelection()
            .filterByDfName(aid)
            .setFileOccurrence(fileOccurrence)
            .acceptInvalidatedCard());
    CardSelectionResult selectionResult =
        cardSelectionManager.processCardSelectionScenario(cardReader);
    CalypsoCard calypsoCard = (CalypsoCard) selectionResult.getActiveSmartCard();
    if (calypsoCard != null) {
      discoverFileStructure(calypsoCard);
      applicationData.setCalypsoCard(calypsoCard);
      applicationData.setFcps(extractFcps(cardTransactionManager.getTransactionAuditData()));
      return applicationData;
    }
    return null;
  }

  /**
   * Discovers the file structure by iteratively selecting the EFs within the current DF.
   *
   * @param calypsoCard A {@link CalypsoCard}
   */
  private static void discoverFileStructure(CalypsoCard calypsoCard) {
    cardTransactionManager =
        calypsoCardService.createCardTransactionWithoutSecurity(cardReader, calypsoCard);
    cardTransactionManager.prepareSelectFile(SelectFileControl.CURRENT_DF);
    cardTransactionManager.prepareGetData(GetDataTag.TRACEABILITY_INFORMATION);
    cardTransactionManager.processCommands();
    Set<ElementaryFile> files = new HashSet<ElementaryFile>();
    boolean dataDiscovered = getFileData(SelectFileControl.FIRST_EF, calypsoCard, files);
    while (dataDiscovered) {
      dataDiscovered = getFileData(SelectFileControl.NEXT_EF, calypsoCard, files);
    }
  }

  /**
   * Gets the data of the targeted EF and places it into the provided {@link CalypsoCard}.
   *
   * @param selectFileControl First or next EF.
   * @param calypsoCard A {@link CalypsoCard}
   * @param currentFiles The already read files.
   * @return true if the operation succeeded, false if not (end of the file structure reached).
   */
  private static boolean getFileData(
      SelectFileControl selectFileControl,
      CalypsoCard calypsoCard,
      Set<ElementaryFile> currentFiles) {
    cardTransactionManager.prepareSelectFile(selectFileControl);
    try {
      cardTransactionManager.processCommands();
    } catch (SelectFileException e) {
      logger.info("No more files found");
      return false;
    }
    ElementaryFile newFile = null;
    Set<ElementaryFile> newFiles = calypsoCard.getFiles();
    // determine which file in newFiles is not in currentFiles
    for (ElementaryFile file : newFiles) {
      if (!currentFiles.contains(file)) {
        newFile = file;
        break;
      }
    }
    // update current files
    currentFiles.add(newFile);
    FileHeader fileHeader = newFile.getHeader();
    if (fileHeader.getEfType() != ElementaryFile.Type.BINARY
        && fileHeader.getAccessConditions()[0] != 1) {
      for (int i = 0; i < fileHeader.getRecordsNumber(); i++) {
        cardTransactionManager.prepareReadRecords(
            (byte) 0, i + 1, i + 1, fileHeader.getRecordSize());
      }
      cardTransactionManager.processCommands();
    }
    return true;
  }

  /**
   * Parses the audit data and only keep responses to XXA402XX.XX APDU commands (select file),
   * returns the FCPs (response without status word).
   *
   * @param auditData The transaction audit data.
   * @return A list of FCPs
   */
  private static List<byte[]> extractFcps(List<byte[]> auditData) {
    List<byte[]> fcps = new ArrayList<byte[]>();
    for (int i = 0; i < auditData.size(); i++) {
      byte[] cmd = auditData.get(i++);
      if (cmd[1] == (byte) 0xA4 && cmd[2] == (byte) 0x02) {
        byte[] rsp = auditData.get(i);
        if (rsp.length > 2) {
          fcps.add(Arrays.copyOf(rsp, rsp.length - 2));
        }
      }
    }
    return fcps;
  }

  private static void displayApplicationData(ApplicationData applicationData) {
    CalypsoCard calypsoCard = applicationData.getCalypsoCard();
    System.out.println(String.format("Product type:    %s", calypsoCard.getProductType().name()));
    System.out.println(
        String.format("DF Name:         %s", HexUtil.toHex(calypsoCard.getDfName())));
    System.out.println(
        String.format(
            "Serial number:   %s", HexUtil.toHex(calypsoCard.getApplicationSerialNumber())));
    System.out.println(
        String.format("File structure:  %sh", HexUtil.toHex(calypsoCard.getApplicationSubtype())));
    System.out.println(
        String.format("Extended mode:   %s", calypsoCard.isExtendedModeSupported() ? "YES" : "NO"));
    System.out.println(
        String.format("PKI mode:        %s", calypsoCard.isPkiModeSupported() ? "YES" : "NO"));
    System.out.println(
        String.format("PIN:             %s", calypsoCard.isPinFeatureAvailable() ? "YES" : "NO"));
    System.out.println(
        String.format("Stored value:    %s", calypsoCard.isSvFeatureAvailable() ? "YES" : "NO"));
    System.out.println(String.format("HCE:             %s", calypsoCard.isHce() ? "YES" : "NO"));
    System.out.println("Directory header:");
    System.out.println(
        String.format(
            "    LID:                  %s",
            HexUtil.toHex(calypsoCard.getDirectoryHeader().getLid())));
    System.out.println(
        String.format(
            "    Access conditions:    %s",
            HexUtil.toHex(calypsoCard.getDirectoryHeader().getAccessConditions())));
    System.out.println(
        String.format(
            "    Key indexes:          %s",
            HexUtil.toHex(calypsoCard.getDirectoryHeader().getKeyIndexes())));
    System.out.println(
        String.format(
            "    KIF personalization:  %s",
            HexUtil.toHex(
                calypsoCard.getDirectoryHeader().getKif(WriteAccessLevel.PERSONALIZATION))));
    System.out.println(
        String.format(
            "    KIF load:             %s",
            HexUtil.toHex(calypsoCard.getDirectoryHeader().getKif(WriteAccessLevel.LOAD))));
    System.out.println(
        String.format(
            "    KIF debit:            %s",
            HexUtil.toHex(calypsoCard.getDirectoryHeader().getKif(WriteAccessLevel.DEBIT))));
    System.out.println(
        String.format(
            "    KVC personalization:  %s",
            HexUtil.toHex(
                calypsoCard.getDirectoryHeader().getKvc(WriteAccessLevel.PERSONALIZATION))));
    System.out.println(
        String.format(
            "    KVC load:             %s",
            HexUtil.toHex(calypsoCard.getDirectoryHeader().getKvc(WriteAccessLevel.LOAD))));
    System.out.println(
        String.format(
            "    KVC debit:            %s",
            HexUtil.toHex(calypsoCard.getDirectoryHeader().getKvc(WriteAccessLevel.DEBIT))));
    System.out.println(
        String.format(
            "Traceability information: %s",
            HexUtil.toHex(calypsoCard.getTraceabilityInformation())));
    int i = 0;
    Set<ElementaryFile> files = calypsoCard.getFiles();
    System.out.println(String.format("%d elementary files found.", files.size()));
    for (ElementaryFile file : files) {
      System.out.println(
          "-----------------------------------------------------------------------------------");
      System.out.println(String.format("File #%d:", i++));
      System.out.println(String.format("    SFI:               %s", HexUtil.toHex(file.getSfi())));
      System.out.println(
          String.format("    LID:               %s", HexUtil.toHex(file.getHeader().getLid())));
      System.out.println(
          String.format("    Type:              %s", file.getHeader().getEfType().name()));
      System.out.println(
          String.format(
              "    Access conditions: %s", HexUtil.toHex(file.getHeader().getAccessConditions())));
      System.out.println(
          String.format(
              "    Key indexes:       %s", HexUtil.toHex(file.getHeader().getKeyIndexes())));
      System.out.println(
          String.format(
              "    Shared reference:  %s", HexUtil.toHex(file.getHeader().getSharedReference())));
      System.out.println(
          String.format("    Number of records: %d", file.getHeader().getRecordsNumber()));
      System.out.println(
          String.format("    Size of record:    %d", file.getHeader().getRecordSize()));
      SortedMap<Integer, byte[]> records = file.getData().getAllRecordsContent();
      for (Map.Entry<Integer, byte[]> e : records.entrySet()) {
        System.out.println(
            String.format("    REC#%d:             %s", e.getKey(), HexUtil.toHex(e.getValue())));
      }
    }
    System.out.println("Raw file descriptors:");
    List<byte[]> fcps = applicationData.getFcps();
    i = 0;
    for (byte[] fcp : fcps) {
      System.out.println(String.format("FCP #%d = %s", i++, HexUtil.toHex(fcp)));
    }
  }
}

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

import org.calypsonet.terminal.calypso.GetDataTag;
import org.calypsonet.terminal.calypso.SelectFileControl;
import org.calypsonet.terminal.calypso.card.CalypsoCard;
import org.calypsonet.terminal.calypso.card.CalypsoCardSelection;
import org.calypsonet.terminal.calypso.card.ElementaryFile;
import org.calypsonet.terminal.calypso.card.FileHeader;
import org.calypsonet.terminal.calypso.transaction.CardTransactionManager;
import org.calypsonet.terminal.calypso.transaction.UnexpectedCommandStatusException;
import org.calypsonet.terminal.reader.CardReader;
import org.calypsonet.terminal.reader.selection.CardSelectionManager;
import org.calypsonet.terminal.reader.selection.CardSelectionResult;
import org.eclipse.keyple.card.calypso.CalypsoExtensionService;
import org.eclipse.keyple.core.service.Plugin;
import org.eclipse.keyple.core.service.SmartCardService;
import org.eclipse.keyple.core.service.SmartCardServiceProvider;
import org.eclipse.keyple.core.util.HexUtil;
import org.eclipse.keyple.core.util.json.JsonUtil;
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
  private static final String AID = "315449432E";

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

    CalypsoCard calypsoCard;

    logger.info("Dumping the file system of all applications whose AID starts with {}...", AID);

    calypsoCard = getApplicationData(CalypsoCardSelection.FileOccurrence.FIRST, AID);

    while (calypsoCard != null) {
      logger.info("= SmartCard = {}", calypsoCard);
      calypsoCard = getApplicationData(CalypsoCardSelection.FileOccurrence.NEXT, AID);
    }

    logger.info("End of processing.");

    System.exit(0);
  }

  /**
   * Gets the data of the application specified by its AID.
   *
   * @param fileOccurrence First or Next application.
   * @param aid A hex string containing the application identifier
   * @return A {@link CalypsoCard}
   */
  private static CalypsoCard getApplicationData(
      CalypsoCardSelection.FileOccurrence fileOccurrence, String aid) {
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
    }
    return calypsoCard;
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
    boolean dataDiscovered = getFileData(SelectFileControl.FIRST_EF, calypsoCard);
    while (dataDiscovered) {
      dataDiscovered = getFileData(SelectFileControl.NEXT_EF, calypsoCard);
    }
  }

  /**
   * Gets the data of the targeted EF and places it into the provided {@link CalypsoCard}.
   *
   * @param selectFileControl First or next EF.
   * @param calypsoCard A {@link CalypsoCard}
   * @return true if the operation succeeded, false if not (end of the file structure reached).
   */
  private static boolean getFileData(SelectFileControl selectFileControl, CalypsoCard calypsoCard) {
    cardTransactionManager.prepareSelectFile(selectFileControl);
    try {
      cardTransactionManager.processCommands();
    } catch (UnexpectedCommandStatusException e) {
      return false;
    }
    FileInfo fileInfo = getCurrentFileInfo(calypsoCard);
    byte currentSfi = HexUtil.toByte(fileInfo.currentSfi);
    FileHeader fileHeader = calypsoCard.getFileBySfi(currentSfi).getHeader();
    if (fileHeader.getEfType() != ElementaryFile.Type.BINARY
        && fileHeader.getAccessConditions()[0] != 1) {
      for (int i = 0; i < fileHeader.getRecordsNumber(); i++) {
        cardTransactionManager.prepareReadRecords(
            currentSfi, i + 1, i + 1, fileHeader.getRecordSize());
      }
      cardTransactionManager.processCommands();
    }
    return true;
  }

  /**
   * Extracts the SFI and LID of the current file.
   *
   * <p>This is a hack using the CalypsoCard Json data since there is no get for these fields.
   *
   * @param calypsoCard A {@link CalypsoCard}
   * @return A {@link FileInfo} object.
   */
  private static FileInfo getCurrentFileInfo(CalypsoCard calypsoCard) {
    String calypsoCardJson = calypsoCard.toString();
    return JsonUtil.getParser().fromJson(calypsoCardJson, FileInfo.class);
  }

  /** Class used to retrieve hidden information in CalypsoCard */
  private static class FileInfo {
    String currentSfi;
    String currentLid;
  }
}

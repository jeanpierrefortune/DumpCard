/* **************************************************************************************
 * Copyright (c) 2022 Calypso Networks Association https://calypsonet.org/
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

import java.util.List;
import org.calypsonet.terminal.calypso.card.CalypsoCard;

class ApplicationData {
  private CalypsoCard calypsoCard;
  private List<byte[]> fcps;

  public ApplicationData() {}

  public void setCalypsoCard(CalypsoCard calypsoCard) {
    this.calypsoCard = calypsoCard;
  }

  public CalypsoCard getCalypsoCard() {
    return calypsoCard;
  }

  public void setFcps(List<byte[]> fcps) {
    this.fcps = fcps;
  }

  public List<byte[]> getFcps() {
    return fcps;
  }
}

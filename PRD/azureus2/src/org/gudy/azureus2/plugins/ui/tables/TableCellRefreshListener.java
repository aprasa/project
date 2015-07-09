/*
 * Created : 2004/Apr/30
 * 
 * Azureus - a Java Bittorrent client
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package org.gudy.azureus2.plugins.ui.tables;

/** A listener that triggers based on refresh interval specified in 
 * {@link TableColumn#getRefreshInterval()}
 *
 * @author TuxPaper
 */
public interface TableCellRefreshListener {
  /** Triggered based on refresh interval specified in {@link TableColumn#getRefreshInterval()}
   *
   * @param cell TableCell that the refresh trigger is for
   */
  void refresh(TableCell cell);
}

/*
 * Copyright (c) 2018 wetransform GmbH
 * 
 * All rights reserved. This program and the accompanying materials are made
 * available under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution. If not, see <http://www.gnu.org/licenses/>.
 * 
 * Contributors:
 *     wetransform GmbH <http://www.wetransform.to>
 */

package eu.esdihumboldt.hale.common.align.merge.test.impl

import static org.junit.Assert.*

import org.junit.Test

import com.google.common.collect.ListMultimap

import eu.esdihumboldt.hale.common.align.merge.impl.DefaultMergeCellMigrator
import eu.esdihumboldt.hale.common.align.merge.test.AbstractMergeCellMigratorTest
import eu.esdihumboldt.hale.common.align.model.Cell
import eu.esdihumboldt.hale.common.align.model.CellUtil
import eu.esdihumboldt.hale.common.align.model.MutableCell
import eu.esdihumboldt.hale.common.align.model.functions.JoinFunction

/**
 * Tests for default merge cell migrator.
 * 
 * @author Simon Templer
 */
class DefaultMergeCellMigratorTest extends AbstractMergeCellMigratorTest {

	@Test
	void mergeJoinWithRetypeDef() {
		// explicitly use default migrator
		def migrator = new DefaultMergeCellMigrator()

		def toMigrate = this.class.getResource('/testcases/sample-hydro/B-to-C.halex')
		def cellId = 'RiverToWatercourse'

		def matching = this.class.getResource('/testcases/sample-hydro/A-to-B.halex')
		def matchId = 'RiverJoin'

		def migrated = mergeWithMigrator(migrator, cellId, toMigrate, matching)

		def original = getProject(toMigrate).alignment.getCell(cellId)
		def match = getProject(matching).alignment.getCell(matchId)

		verifyJoinRetype(migrated, original, match)
	}

	@Test
	void mergeJoinWithRetype() {
		def toMigrate = this.class.getResource('/testcases/sample-hydro/B-to-C.halex')
		def cellId = 'RiverToWatercourse'

		def matching = this.class.getResource('/testcases/sample-hydro/A-to-B.halex')
		def matchId = 'RiverJoin'

		def migrated = merge(cellId, toMigrate, matching)

		def original = getProject(toMigrate).alignment.getCell(cellId)
		def match = getProject(matching).alignment.getCell(matchId)

		verifyJoinRetype(migrated, original, match)
	}

	private void verifyJoinRetype(List<MutableCell> migrated, Cell original, Cell match) {
		// number of cells
		assertEquals('Join and Retype should be combined to one cell', 1, migrated.size())
		def cell = migrated[0]
		assertNotNull(cell)

		// transformation function
		assertEquals('Join and Retype should be combined to a Join', JoinFunction.ID, cell.transformationIdentifier)

		// target (should be same as of Retype)
		ListMultimap<String, Object> targets = cell.target
		assertNotNull(targets)
		assertEquals(1, targets.size())
		def target = CellUtil.getFirstEntity(targets)
		assertNotNull(target)
		def orgTarget = CellUtil.getFirstEntity(original.target)
		assertNotNull(orgTarget)
		assertEquals('Target of new Join must be the same as the target of the Retype', orgTarget, target)

		// sources (should be same as of Join)
		def sources = cell.source
		def matchSources = match.source
		assertEquals('Sources must be the same as for the matching Join', matchSources, sources)

		// parameters

		// Join type order (should be the same as for the matching cell)
		def typeOrder = CellUtil.getFirstParameter(cell, JoinFunction.JOIN_TYPES)
		assertNotNull(typeOrder)
		def matchTypeOrder = CellUtil.getFirstParameter(match, JoinFunction.JOIN_TYPES)
		assertEquals('Join order must be the same as for the matching Join', matchTypeOrder, typeOrder)

		// Join conditions (should be the same as for the matching cell)
		def conditions = CellUtil.getFirstParameter(cell, JoinFunction.PARAMETER_JOIN)
		assertNotNull(conditions)
		def matchConditions = CellUtil.getFirstParameter(match, JoinFunction.PARAMETER_JOIN)
		assertEquals('Join conditions must be the same as for the matching Join', matchConditions, conditions)
	}

	//TODO more tests
}
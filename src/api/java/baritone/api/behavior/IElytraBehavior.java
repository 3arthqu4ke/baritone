/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.api.behavior;

import net.minecraft.util.math.BlockPos;

import java.util.concurrent.CompletableFuture;

public interface IElytraBehavior extends IBehavior {

    /**
     * Marks the nether pathfinder context to be reset when it is safe to do so. Because this operation is not
     * immediate, a {@link CompletableFuture} is returned that will complete after the context has been reset.
     *
     * @return A {@link CompletableFuture} that is completed when the context is reset
     */
    CompletableFuture<Void> resetContext();

    void repackChunks();

    void pathTo(BlockPos destination);

    void cancel();

    /**
     * Returns {@code true} if the current {@link IElytraBehavior} is actively pathing.
     */
    boolean isActive();
}

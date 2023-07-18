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

package baritone.process;

import baritone.Baritone;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalYLevel;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.movements.MovementFall;
import baritone.process.elytra.LegacyElytraBehavior;
import baritone.process.elytra.NetherPathfinderContext;
import baritone.process.elytra.NullElytraProcess;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.PathingCommandContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;


public class ElytraProcess extends BaritoneProcessHelper implements IBaritoneProcess, IElytraProcess, AbstractGameEventListener {
    public State state;
    private Goal goal;
    private LegacyElytraBehavior behavior;

    private ElytraProcess(Baritone baritone) {
        super(baritone);
        this.behavior = new LegacyElytraBehavior(baritone, this);
        baritone.getGameEventHandler().registerEventListener(this);
    }

    public static <T extends IElytraProcess> T create(final Baritone baritone) {
        return (T) (NetherPathfinderContext.isSupported()
                ? new ElytraProcess(baritone)
                : new NullElytraProcess(baritone));
    }



    @Override
    public boolean isActive() {
        return behavior != null;
    }

    @Override
    public void resetState() {
        BlockPos destination = this.currentDestination();
        this.onLostControl();
        this.pathTo(destination);
        this.repackChunks();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        final long seedSetting = Baritone.settings().elytraNetherSeed.value;
        if (this.behavior != null && seedSetting != behavior.context.getSeed()) {
            logDirect("Nether seed changed, recalculating path");
            this.resetState();
        }

        if (this.behavior != null) {
            this.behavior.onTick();
        }
        if (calcFailed) {
            onLostControl();
            logDirect("Failed to get to jump off spot, canceling");
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (ctx.player().isElytraFlying()) {
            final BetterBlockPos last = behavior.pathManager.path.getLast();
            if (last != null && ctx.player().getDistanceSqToCenter(last) < (5 * 5)) {
                this.state = State.LANDING;
            }
        }

        if (this.state == State.LANDING) {
            final BetterBlockPos endPos = behavior.pathManager.path.getLast();
            if (ctx.player().isElytraFlying() && endPos != null) {
                Vec3d from = ctx.player().getPositionVector();
                Vec3d to = new Vec3d(endPos.x, from.y, endPos.z);
                Rotation rotation = RotationUtils.calcRotationFromVec3d(from, to, ctx.playerRotations());
                baritone.getLookBehavior().updateTarget(rotation, false);
            } else {
                this.onLostControl();
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }
        } else if (ctx.player().isElytraFlying()) {
            this.state = State.FLYING;
            this.goal = null;
            baritone.getInputOverrideHandler().clearAllKeys();
            behavior.tick();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        if (this.state == State.FLYING || this.state == State.START_FLYING) {
            this.state = ctx.player().onGround && Baritone.settings().elytraAutoJump.value
                    ? State.LOCATE_JUMP
                    : State.START_FLYING;
        }

        if (this.state == State.LOCATE_JUMP) {
            if (this.goal == null) {
                this.goal = new GoalYLevel(31);
            }
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            if (executor != null && executor.getPath().getGoal() == this.goal) {
                final IMovement fall = executor.getPath().movements().stream()
                        .filter(movement -> movement instanceof MovementFall)
                        .findFirst().orElse(null);

                if (fall != null) {
                    final BetterBlockPos from = new BetterBlockPos(
                            (fall.getSrc().x + fall.getDest().x) / 2,
                            (fall.getSrc().y + fall.getDest().y) / 2,
                            (fall.getSrc().z + fall.getDest().z) / 2
                    );
                    behavior.pathManager.pathToDestination(from).whenComplete((result, ex) -> {
                        if (!behavior.clearView(new Vec3d(from), behavior.pathManager.getPath().getVec(0), false)) {
                            onLostControl();
                            // TODO: Get to higher ground and then look again
                            logDirect("Can't see start of path from jump spot, canceling");
                            return;
                        }
                        if (ex == null) {
                            this.state = State.GET_TO_JUMP;
                            return;
                        }
                        onLostControl();
                    });
                    this.state = State.PAUSE;
                } else {
                    onLostControl();
                    logDirect("Jump off path didn't include a fall movement, canceling");
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
            return new PathingCommandContext(this.goal, PathingCommandType.SET_GOAL_AND_PAUSE, new LegacyElytraBehavior.WalkOffCalculationContext(baritone));
        }

        // yucky
        if (this.state == State.PAUSE) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (this.state == State.GET_TO_JUMP) {
            final IPathExecutor executor = baritone.getPathingBehavior().getCurrent();
            final boolean canStartFlying = ctx.player().fallDistance > 1.0f
                    && !isSafeToCancel
                    && executor != null
                    && executor.getPath().movements().get(executor.getPosition()) instanceof MovementFall;

            if (canStartFlying) {
                this.state = State.START_FLYING;
            } else {
                return new PathingCommand(null, PathingCommandType.SET_GOAL_AND_PATH);
            }
        }

        if (this.state == State.START_FLYING) {
            if (!isSafeToCancel) {
                // owned
                baritone.getPathingBehavior().secretInternalSegmentCancel();
            }
            baritone.getInputOverrideHandler().clearAllKeys();
            if (ctx.player().fallDistance > 1.0f) {
                baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            }
        }
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    @Override
    public void onLostControl() {
        this.goal = null;
        this.state = State.START_FLYING; // TODO: null state?
        if (this.behavior != null) this.behavior.destroy();
        this.behavior = null;
    }

    @Override
    public String displayName0() {
        return "Elytra - " + this.state.description;
    }

    @Override
    public void repackChunks() {
        this.behavior.repackChunks();
    }

    @Override
    public BlockPos currentDestination() {
        return this.behavior != null ? this.behavior.destination : null;
    }

    @Override
    public void pathTo(BlockPos destination) {
        this.behavior = new LegacyElytraBehavior(this.baritone, this);
        if (ctx.world() != null) {
            this.behavior.repackChunks();
        }
        this.behavior.pathTo(destination);
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public boolean isSafeToCancel() {
        return !this.isActive() || !(this.state == State.FLYING || this.state == State.START_FLYING);
    }

    public enum State {
        LOCATE_JUMP("Finding spot to jump off"),
        VALIDATE_PATH("Validating path"),
        PAUSE("Waiting for elytra path"),
        GET_TO_JUMP("Walking to takeoff"),
        START_FLYING("Begin flying"),
        FLYING("Flying"),
        LANDING("Landing");

        public final String description;

        State(String desc) {
            this.description = desc;
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (this.behavior != null) this.behavior.onRenderPass(event);
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (event.getWorld() != null && event.getState() == EventState.POST) {
            // Exiting the world, just destroy
            if (this.behavior != null) {
                this.behavior.destroy();
                this.behavior = new LegacyElytraBehavior(baritone, this);
            }
        }
    }

    @Override
    public void onChunkEvent(ChunkEvent event) {
        if (this.behavior != null) this.behavior.onChunkEvent(event);
    }

    @Override
    public void onBlockChange(BlockChangeEvent event) {
        if (this.behavior != null) this.behavior.onBlockChange(event);
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (this.behavior != null) this.behavior.onReceivePacket(event);
    }

    @Override
    public void onPostTick(TickEvent event) {
        IBaritoneProcess procThisTick = baritone.getPathingControlManager().mostRecentInControl().orElse(null);
        if (this.behavior != null && procThisTick == this) this.behavior.onPostTick(event);
    }
}

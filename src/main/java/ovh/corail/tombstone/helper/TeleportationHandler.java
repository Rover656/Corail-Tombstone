package ovh.corail.tombstone.helper;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.play.server.SSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.ForgeHooks;

import java.util.LinkedList;
import java.util.UUID;

public class TeleportationHandler {
    public static Entity teleportEntity(Entity entity, DimensionType targetDimType, double xCoord, double yCoord, double zCoord) {
        return teleportEntity(entity, targetDimType, xCoord, yCoord, zCoord, entity.rotationYaw, entity.rotationPitch);
    }

    public static Entity teleportEntity(Entity entity, DimensionType targetDimType, double xCoord, double yCoord, double zCoord, float yaw, float pitch) {
        if (!entity.world.isRemote && entity.isAlive()) {
            MinecraftServer server = entity.getServer();
            if (server != null) {
                DimensionType sourceDimType = entity.world.dimension.getType();
                if (!entity.isBeingRidden() && !entity.isPassenger()) {
                    return teleportEntity(entity, server, sourceDimType, targetDimType, xCoord, yCoord, zCoord, yaw, pitch);
                }
                Entity lowestRidingEntity = entity.getLowestRidingEntity();
                PassengerHelper passengerHelper = new PassengerHelper(lowestRidingEntity);
                PassengerHelper rider = passengerHelper.getPassenger(entity);
                if (rider == null) {
                    return entity;
                }
                passengerHelper.teleport(server, sourceDimType, targetDimType, xCoord, yCoord, zCoord, yaw, pitch);
                passengerHelper.remountRiders();
                passengerHelper.updateClients();

                return rider.entity;
            }
        }
        return entity;
    }

    private static Entity teleportEntity(Entity entity, MinecraftServer server, DimensionType sourceDim, DimensionType targetDim, double xCoord, double yCoord, double zCoord, float yaw, float pitch) {
        if (entity == null || !entity.isAlive()) {
            return entity;
        }
        boolean interDimensional = sourceDim != targetDim;
        boolean isPlayer = entity instanceof ServerPlayerEntity;
        if (interDimensional) {
            if (ForgeHooks.onTravelToDimension(entity, targetDim)) {
                if (isPlayer) {
                    return teleportPlayerInterdimensional((ServerPlayerEntity) entity, server, server.getWorld(targetDim), xCoord, yCoord, zCoord, yaw, pitch);
                } else {
                    return teleportEntityInterdimensional(entity, server.getWorld(sourceDim), server.getWorld(targetDim), xCoord, yCoord, zCoord, yaw, pitch);
                }
            }
        } else if (isPlayer) {
            ServerPlayerEntity player = (ServerPlayerEntity) entity;
            player.connection.setPlayerLocation(xCoord, yCoord, zCoord, yaw, pitch);
            player.setRotationYawHead(yaw);
        } else {
            entity.setLocationAndAngles(xCoord, yCoord, zCoord, yaw, pitch);
            entity.setRotationYawHead(yaw);
        }
        return entity;
    }

    private static ServerPlayerEntity teleportPlayerInterdimensional(ServerPlayerEntity player, MinecraftServer server, ServerWorld targetWorld, double xCoord, double yCoord, double zCoord, float yaw, float pitch) {
        UUID id = player.getUniqueID();
        player.teleport(targetWorld, xCoord, yCoord, zCoord, yaw, pitch);
        return server.getPlayerList().getPlayerByUUID(id);
    }

    private static Entity teleportEntityInterdimensional(Entity entity, ServerWorld sourceWorld, ServerWorld targetWorld, double xCoord, double yCoord, double zCoord, float yaw, float pitch) {
        DimensionType targetDim = Helper.getDimensionType(targetWorld);
        Vec3d motion = entity.getMotion();
        entity.dimension = targetDim;
        entity.detach();

        Entity newEntity = entity.getType().create(targetWorld);
        if (newEntity != null) {
            CompoundNBT nbt = new CompoundNBT();
            entity.writeUnlessRemoved(nbt);
            nbt.remove("Dimension");
            newEntity.read(nbt);
            newEntity.timeUntilPortal = entity.timeUntilPortal;
            newEntity.setLocationAndAngles(xCoord, yCoord, zCoord, yaw, pitch);
            newEntity.setMotion(motion);
            targetWorld.addFromAnotherDimension(newEntity);
            sourceWorld.resetUpdateEntityTick();
            targetWorld.resetUpdateEntityTick();
        }

        entity.remove(false);
        return newEntity;
    }

    /* based on the library of Brandon Core with permission */
    private static class PassengerHelper {
        public Entity entity;
        final LinkedList<PassengerHelper> passengers = new LinkedList<>();
        double offsetX, offsetY, offsetZ;

        PassengerHelper(Entity entity) {
            this.entity = entity;
            if (entity.isPassenger()) {
                offsetX = entity.getPosX() - entity.getRidingEntity().getPosX();
                offsetY = entity.getPosY() - entity.getRidingEntity().getPosY();
                offsetZ = entity.getPosZ() - entity.getRidingEntity().getPosZ();
            }
            for (Entity passenger : entity.getPassengers()) {
                passengers.add(new PassengerHelper(passenger));
            }
        }

        void teleport(MinecraftServer server, DimensionType sourceDim, DimensionType targetDim, double xCoord, double yCoord, double zCoord, float yaw, float pitch) {
            entity.removePassengers();
            entity = teleportEntity(entity, server, sourceDim, targetDim, xCoord, yCoord, zCoord, yaw, pitch);
            for (PassengerHelper passenger : passengers) {
                passenger.teleport(server, sourceDim, targetDim, xCoord, yCoord, zCoord, yaw, pitch);
            }
        }

        void remountRiders() {
            if (entity == null) {
                return;
            }
            if (entity.isPassenger()) {
                entity.setLocationAndAngles(entity.getPosX() + offsetX, entity.getPosY() + offsetY, entity.getPosZ() + offsetZ, entity.rotationYaw, entity.rotationPitch);
            }
            for (PassengerHelper passenger : passengers) {
                passenger.entity.startRiding(entity, true);
                passenger.remountRiders();
            }
        }

        void updateClients() {
            if (entity instanceof ServerPlayerEntity) {
                updateClient((ServerPlayerEntity) entity);
            }
            for (PassengerHelper passenger : passengers) {
                passenger.updateClients();
            }
        }

        private void updateClient(ServerPlayerEntity playerMP) {
            if (entity.isBeingRidden()) {
                playerMP.connection.sendPacket(new SSetPassengersPacket(entity));
            }
            for (PassengerHelper passenger : passengers) {
                passenger.updateClients();
            }
        }

        PassengerHelper getPassenger(Entity passenger) {
            if (this.entity == passenger) {
                return this;
            }
            for (PassengerHelper rider : passengers) {
                PassengerHelper re = rider.getPassenger(passenger);
                if (re != null) {
                    return re;
                }
            }
            return null;
        }
    }
}

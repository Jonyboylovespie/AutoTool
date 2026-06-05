package com.autotool;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class AutoToolClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		AttackBlockCallback.EVENT.register(this::onAttackBlock);
	}

	private InteractionResult onAttackBlock(Player player, Level level, InteractionHand hand,
											BlockPos pos, Direction direction) {
		// Only run on the client and for non-creative, non-spectator players.
		if (!level.isClientSide()) {
			return InteractionResult.PASS;
		}
		if (player.isCreative() || player.isSpectator()) {
			return InteractionResult.PASS;
		}

		BlockState state = level.getBlockState(pos);
		Inventory inventory = player.getInventory();
		int currentSlot = inventory.getSelectedSlot();
		ItemStack currentStack = inventory.getItem(currentSlot);

		// Only auto-switch if the player is already holding an item with tool properties.
		if (currentStack.get(DataComponents.TOOL) == null) {
			return InteractionResult.PASS;
		}

		// Don't treat swords as tools — they're weapons.
		if (currentStack.is(ItemTags.SWORDS)) {
			return InteractionResult.PASS;
		}

		float currentSpeed = currentStack.getDestroySpeed(state);
		boolean currentIsCorrect = currentStack.isCorrectToolForDrops(state);

		int bestSlot = -1;
		float bestSpeed = -1.0f;
		boolean bestIsCorrect = false;

		// Search the player's main inventory (36 slots: hotbar 0-8, main 9-35).
		for (int slot = 0; slot < 36; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}

			float speed = stack.getDestroySpeed(state);
			boolean isCorrect = stack.isCorrectToolForDrops(state);

			if (state.requiresCorrectToolForDrops()) {
				if (isCorrect && !bestIsCorrect) {
					// First correct tool we've encountered.
					bestIsCorrect = true;
					bestSpeed = speed;
					bestSlot = slot;
					continue;
				}

				if (isCorrect == bestIsCorrect && speed > bestSpeed) {
					bestSpeed = speed;
					bestSlot = slot;
				}
				// If this tool is incorrect and we already have a correct one, skip it.
			} else {
				if (speed > bestSpeed) {
					bestSpeed = speed;
					bestSlot = slot;
				}
			}
		}

		// No suitable tool found or already holding the best tool.
		if (bestSlot == -1 || bestSlot == currentSlot) {
			return InteractionResult.PASS;
		}

		// Determine whether the found tool is actually better.
		boolean shouldSwap;
		if (state.requiresCorrectToolForDrops()) {
			if (currentIsCorrect && !bestIsCorrect) {
				shouldSwap = false;
			} else if (!currentIsCorrect && bestIsCorrect) {
				shouldSwap = true;
			} else {
				shouldSwap = bestSpeed > currentSpeed;
			}
		} else {
			shouldSwap = bestSpeed > currentSpeed;
		}

		if (!shouldSwap) {
			return InteractionResult.PASS;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.getConnection() == null) {
			return InteractionResult.PASS;
		}

		if (Inventory.isHotbarSlot(bestSlot)) {
			if (currentSlot != bestSlot) {
				inventory.setSelectedSlot(bestSlot);
				client.getConnection().send(new ServerboundSetCarriedItemPacket(bestSlot));
			}
		} else {
			// Find the container menu slot corresponding to the inventory slot.
			int menuSlot = -1;
			for (int i = 0; i < player.inventoryMenu.slots.size(); i++) {
				if (player.inventoryMenu.slots.get(i).getContainerSlot() == bestSlot) {
					menuSlot = i;
					break;
				}
			}

			if (menuSlot < 0) {
				return InteractionResult.PASS;
			}

			client.gameMode.handleContainerInput(player.inventoryMenu.containerId,
				menuSlot, currentSlot, ContainerInput.SWAP, player);
			client.getConnection().send(new ServerboundSetCarriedItemPacket(currentSlot));
		}

		return InteractionResult.PASS;
	}
}

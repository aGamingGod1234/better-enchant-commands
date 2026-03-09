package com.agaminggod.betterenchantcommands.verification;

import com.agaminggod.betterenchantcommands.BetterEnchantCommands;
import com.agaminggod.betterenchantcommands.compat.MinecraftCompatibility;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

public final class InGameStressVerifier {
    private static final int STRESS_ITERATIONS = 500;
    private static final int ENCHANT_MIN_LEVEL = 1;
    private static final String SHARPNESS_ID = "minecraft:sharpness";
    private static final String FAKE_PLAYER_NAME = "ep_stress_bot";
    private static final String PASS_PREFIX = "[Better Enchant Commands Stress PASS] ";
    private static final String FAIL_PREFIX = "[Better Enchant Commands Stress FAIL] ";

    private InGameStressVerifier() {
    }

    public static void run(final MinecraftServer server) {
        server.execute(() -> runOnServerThread(server));
    }

    private static void runOnServerThread(final MinecraftServer server) {
        final VerificationCounter counter = new VerificationCounter();

        try {
            final ServerLevel level = server.overworld();
            final FakePlayer fakePlayer = FakePlayer.get(level, new GameProfile(UUID.randomUUID(), FAKE_PLAYER_NAME));
            final CommandSourceStack source = MinecraftCompatibility.withPermissionLevel(
                server.createCommandSourceStack()
                    .withEntity(fakePlayer)
                    .withPosition(fakePlayer.position()),
                2
            )
                .withSuppressedOutput();

            // Ensure player has an item for enchant tests.
            fakePlayer.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));

            assertSuccess(counter, source, server, "give @s minecraft:stone 64", "plain /give");
            assertSuccess(counter, source, server, "give @s minecraft:diamond_sword enchantments:sharpness:10,unbreaking:3",
                "/give with enchantments as 3rd argument");
            assertSuccess(counter, source, server, "give @s minecraft:diamond_sword 1 enchantments:sharpness:255,unbreaking:3",
                "/give with enchantments as 4th argument");

            assertSuccess(counter, source, server, "enchant @s minecraft:sharpness 255", "/enchant high level 255");
            assertSuccess(counter, source, server, "enchant @s minecraft:sharpness", "/enchant default level path");
            assertSharpnessLevel(counter, server, fakePlayer.getMainHandItem(), ENCHANT_MIN_LEVEL,
                "default /enchant applies level 1");

            assertFailure(counter, source, server, "enchant @s minecraft:sharpness 0", "/enchant rejects level 0");
            assertFailure(counter, source, server, "enchant @s minecraft:sharpness 256", "/enchant rejects level 256");
            assertFailure(counter, source, server, "enchant @s minecraft:unknown_enchant 5", "/enchant rejects unknown id");
            assertFailure(counter, source, server, "give @s minecraft:diamond_sword enchantments:sharpness:0",
                "/give rejects invalid enchant level");
            assertFailure(counter, source, server, "give @s minecraft:diamond_sword enchantments:unknown_enchant:5",
                "/give rejects unknown enchantment");
            assertFailure(counter, source, server, "give @s minecraft:diamond_sword enchantments:badformat",
                "/give rejects malformed enchantment token");

            runStressLoop(counter, source, server);
        } catch (Exception exception) {
            counter.fail();
            BetterEnchantCommands.LOGGER.error("{}Unhandled exception during in-game stress verification", FAIL_PREFIX, exception);
        }

        final int totalChecks = counter.total();
        BetterEnchantCommands.LOGGER.info("{}Completed {} checks (pass={}, fail={})", PASS_PREFIX, totalChecks, counter.passed, counter.failed);

        if (counter.failed > 0) {
            BetterEnchantCommands.LOGGER.error("{}Verification finished with failures", FAIL_PREFIX);
        } else {
            BetterEnchantCommands.LOGGER.info("{}All in-game stress verifications passed", PASS_PREFIX);
        }

        server.halt(false);
    }

    private static void runStressLoop(
        final VerificationCounter counter,
        final CommandSourceStack source,
        final MinecraftServer server
    ) {
        for (int i = 1; i <= STRESS_ITERATIONS; i++) {
            assertSuccess(counter, source, server, "give @s minecraft:stone 64", "stress plain /give #" + i);
            assertSuccess(counter, source, server,
                "give @s minecraft:diamond_sword 1 enchantments:sharpness:255,unbreaking:3,fire_aspect:2",
                "stress /give enchanted #" + i);
            assertSuccess(counter, source, server, "enchant @s minecraft:sharpness 255", "stress /enchant #" + i);

            if (i % 25 == 0) {
                assertFailure(counter, source, server, "give @s minecraft:diamond_sword enchantments:sharpness:999",
                    "stress invalid level /give #" + i);
            }
        }

        if (counter.failed > 0) {
            BetterEnchantCommands.LOGGER.error("{}Stress loop cumulative failures={}", FAIL_PREFIX, counter.failed);
        } else {
            BetterEnchantCommands.LOGGER.info("{}Stress loop passed {} checks", PASS_PREFIX, counter.passed);
        }
    }

    private static void assertSharpnessLevel(
        final VerificationCounter counter,
        final MinecraftServer server,
        final ItemStack stack,
        final int expectedLevel,
        final String label
    ) {
        try {
            final Registry<Enchantment> registry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            final Identifier id = Identifier.parse(SHARPNESS_ID);
            final Holder<Enchantment> holder = MinecraftCompatibility.findRegistryHolderById(registry, id);

            if (holder == null) {
                BetterEnchantCommands.LOGGER.error("{}{}: sharpness holder not found", FAIL_PREFIX, label);
                counter.fail();
                return;
            }

            final ItemEnchantments enchantments = MinecraftCompatibility.getComponentOrDefault(
                stack,
                DataComponents.ENCHANTMENTS,
                ItemEnchantments.EMPTY
            );
            final int level = enchantments.getLevel(holder);

            if (level != expectedLevel) {
                BetterEnchantCommands.LOGGER.error("{}{}: expected level {}, got {}", FAIL_PREFIX, label, expectedLevel, level);
                counter.fail();
                return;
            }

            BetterEnchantCommands.LOGGER.info("{}{}", PASS_PREFIX, label);
            counter.pass();
        } catch (Exception exception) {
            BetterEnchantCommands.LOGGER.error("{}{} threw exception", FAIL_PREFIX, label, exception);
            counter.fail();
        }
    }

    private static void assertSuccess(
        final VerificationCounter counter,
        final CommandSourceStack source,
        final MinecraftServer server,
        final String command,
        final String label
    ) {
        try {
            final int result = server.getCommands().getDispatcher().execute(normalizeCommand(command), source);
            if (result <= 0) {
                BetterEnchantCommands.LOGGER.error("{}{} returned {}", FAIL_PREFIX, label, result);
                counter.fail();
                return;
            }

            counter.pass();
        } catch (CommandSyntaxException exception) {
            BetterEnchantCommands.LOGGER.error("{}{} command syntax error: {}", FAIL_PREFIX, label, exception.getMessage());
            counter.fail();
        } catch (Exception exception) {
            BetterEnchantCommands.LOGGER.error("{}{} threw exception", FAIL_PREFIX, label, exception);
            counter.fail();
        }
    }

    private static void assertFailure(
        final VerificationCounter counter,
        final CommandSourceStack source,
        final MinecraftServer server,
        final String command,
        final String label
    ) {
        try {
            final int result = server.getCommands().getDispatcher().execute(normalizeCommand(command), source);
            if (result > 0) {
                BetterEnchantCommands.LOGGER.error("{}{} unexpectedly returned success {}", FAIL_PREFIX, label, result);
                counter.fail();
                return;
            }

            counter.pass();
        } catch (Exception exception) {
            // Invalid command syntax may throw; this is still a valid failure path for negative tests.
            counter.pass();
        }
    }

    private static String normalizeCommand(final String command) {
        if (command.startsWith("/")) {
            return command.substring(1);
        }

        return command;
    }

    private static final class VerificationCounter {
        private int passed;
        private int failed;

        private void pass() {
            passed++;
        }

        private void fail() {
            failed++;
        }

        private int total() {
            return passed + failed;
        }
    }
}


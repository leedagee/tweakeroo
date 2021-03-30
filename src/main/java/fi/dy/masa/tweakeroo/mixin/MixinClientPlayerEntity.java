package fi.dy.masa.tweakeroo.mixin;

import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.Hand;
import fi.dy.masa.tweakeroo.config.Configs;
import fi.dy.masa.tweakeroo.config.FeatureToggle;
import fi.dy.masa.tweakeroo.util.CameraEntity;
import fi.dy.masa.tweakeroo.util.CameraUtils;
import fi.dy.masa.tweakeroo.util.DummyMovementInput;

@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntity extends AbstractClientPlayerEntity
{
    @Shadow public Input input;
    @Shadow protected int ticksLeftToDoubleTapSprint;

    private final DummyMovementInput dummyMovementInput = new DummyMovementInput(null);
    private Input realInput;
    private ItemStack bookDupePayload;

    public MixinClientPlayerEntity(ClientWorld worldIn, GameProfile playerProfile)
    {
        super(worldIn, playerProfile);
    }

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void onSendChatMessage(String message, CallbackInfo ci)
    {
        if (FeatureToggle.TWEAK_BOOK_DUPE.getBooleanValue() && message.equals("tweakeroo-dupe"))
        {
            MinecraftClient mc = MinecraftClient.getInstance();
            if(mc.player.getMainHandStack().getItem() != Items.WRITABLE_BOOK)
            {
                InfoUtils.showGuiAndInGameMessage(Message.MessageType.WARNING, "tweakeroo.message.bookdupe.you_must_hold_a_writable_book");
                return;
            }
            if(bookDupePayload == null)
            {
                ListTag pages = new ListTag();
                StringBuilder firstPageBuilder = new StringBuilder();
                for(int i = 0; i < 21845; i++)
                    firstPageBuilder.append((char)2077);
                StringBuilder otherPageBuilder = new StringBuilder();
                for(int i = 0; i < 256; i++)
                    otherPageBuilder.append(".");
                pages.addTag(0, StringTag.of(firstPageBuilder.toString()));
                String otherPageString = otherPageBuilder.toString();
                for(int i = 1; i < 40; i++)
                    pages.addTag(i, StringTag.of(otherPageString));
                bookDupePayload = new ItemStack(Items.WRITABLE_BOOK, 1);
                bookDupePayload.putSubTag("title", StringTag.of("strange book"));
                bookDupePayload.putSubTag("pages", pages);
            }
            mc.player.networkHandler.sendPacket(new BookUpdateC2SPacket(
                    bookDupePayload,
                    true,
                    mc.player.inventory.selectedSlot
            ));
            ci.cancel();
        }
    }

    @Redirect(method = "updateNausea()V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/gui/screen/Screen;isPauseScreen()Z"))
    private boolean onDoesGuiPauseGame(Screen gui)
    {
        // Spoof the return value to prevent entering the if block
        if (Configs.Disable.DISABLE_PORTAL_GUI_CLOSING.getBooleanValue())
        {
            return true;
        }

        return gui.isPauseScreen();
    }

    @Inject(method = "tickMovement", at = @At(value = "INVOKE", ordinal = 0, shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/network/ClientPlayNetworkHandler;sendPacket(Lnet/minecraft/network/Packet;)V"))
    private void fixElytraDeployment(CallbackInfo ci)
    {
        if (Configs.Fixes.ELYTRA_FIX.getBooleanValue() && this.isSubmergedInWater() == false)
        {
            this.setFlag(7, true);
        }
    }

    @Inject(method = "tickMovement", at = @At(value = "FIELD",
                target = "Lnet/minecraft/entity/player/PlayerAbilities;allowFlying:Z", ordinal = 1))
    private void overrideSprint(CallbackInfo ci)
    {
        if (FeatureToggle.TWEAK_PERMANENT_SPRINT.getBooleanValue() &&
            ! this.isSprinting() && ! this.isUsingItem() && this.input.movementForward >= 0.8F &&
            (this.getHungerManager().getFoodLevel() > 6.0F || this.abilities.allowFlying) &&
            ! this.hasStatusEffect(StatusEffects.BLINDNESS))
        {
            this.setSprinting(true);
        }
    }

    @Redirect(method = "tickMovement", at = @At(value = "FIELD",
                target = "Lnet/minecraft/client/network/ClientPlayerEntity;horizontalCollision:Z"))
    private boolean overrideCollidedHorizontally(ClientPlayerEntity player)
    {
        if (Configs.Disable.DISABLE_WALL_UNSPRINT.getBooleanValue())
        {
            return false;
        }

        return player.horizontalCollision;
    }

    @Inject(method = "tickMovement",
            slice = @Slice(from = @At(value = "INVOKE",
                                      target = "Lnet/minecraft/client/network/ClientPlayerEntity;getHungerManager()" +
                                               "Lnet/minecraft/entity/player/HungerManager;")),
            at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD, ordinal = 0, shift = At.Shift.AFTER,
                     target = "Lnet/minecraft/client/network/ClientPlayerEntity;ticksLeftToDoubleTapSprint:I"))
    private void disableDoubleTapSprint(CallbackInfo ci)
    {
        if (Configs.Disable.DISABLE_DOUBLE_TAP_SPRINT.getBooleanValue())
        {
            this.ticksLeftToDoubleTapSprint = 0;
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void disableMovementInputsPre(CallbackInfo ci)
    {
        if (CameraUtils.shouldPreventPlayerMovement())
        {
            this.realInput = this.input;
            this.input = this.dummyMovementInput;
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void disableMovementInputsPost(CallbackInfo ci)
    {
        if (this.realInput != null)
        {
            this.input = this.realInput;
            this.realInput = null;
        }
    }

    @Inject(method = "isCamera", at = @At("HEAD"), cancellable = true)
    private void allowPlayerMovementInFreeCameraMode(CallbackInfoReturnable<Boolean> cir)
    {
        if (FeatureToggle.TWEAK_FREE_CAMERA.getBooleanValue() && CameraEntity.originalCameraWasPlayer())
        {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "swingHand", at = @At("HEAD"), cancellable = true)
    private void preventHandSwing(Hand hand, CallbackInfo ci)
    {
        if (CameraUtils.shouldPreventPlayerInputs())
        {
            ci.cancel();
        }
    }
}

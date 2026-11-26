package ws.fabricmod.networksocket.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import ws.fabricmod.networksocket.config.ConnectionStatus;
import ws.fabricmod.networksocket.network.payload.BlockNameResultS2C;
import ws.fabricmod.networksocket.network.payload.SetBlockNameC2S;
import ws.fabricmod.networksocket.protocol.ProtocolConstants;

import java.nio.charset.StandardCharsets;

/**
 * Right-click configuration for one TCP interface block. Only the name is edited here;
 * UI1/UI2 are opened with the config keybinding.
 */
public class BlockConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 320;
    private static final int PANEL_HEIGHT = 170;

    private final BlockPos pos;
    private String name;
    private ConnectionStatus status;
    private EditBox nameBox;
    private Button saveButton;
    private Component feedback;
    private boolean feedbackSuccess;

    public BlockConfigScreen(BlockPos pos, String name, int statusOrdinal) {
        super(Component.translatable("screen.network-socket.block_title"));
        this.pos = pos;
        this.name = name == null ? "" : name;
        this.status = ConnectionStatus.byOrdinal(statusOrdinal);
    }

    private int panelX() {
        return Math.max(0, (width - PANEL_WIDTH) / 2);
    }

    private int panelY() {
        return Math.max(0, (height - PANEL_HEIGHT) / 2);
    }

    @Override
    protected void init() {
        int x = panelX();
        int y = panelY();

        nameBox = new EditBox(this.font, x + 20, y + 55, PANEL_WIDTH - 40, 20, Component.empty());
        nameBox.setMaxLength(256);
        nameBox.setValue(name);
        nameBox.setResponder(value -> updateSaveState());
        addRenderableWidget(nameBox);

        saveButton = Button.builder(Component.translatable("screen.network-socket.save"), button -> save())
                .bounds(x + 20, y + PANEL_HEIGHT - 30, 130, 20).build();
        addRenderableWidget(saveButton);
        addRenderableWidget(Button.builder(Component.translatable("screen.network-socket.close"), button -> onClose())
                .bounds(x + PANEL_WIDTH - 150, y + PANEL_HEIGHT - 30, 130, 20).build());
        updateSaveState();
    }

    private static int nameByteLength(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private void updateSaveState() {
        if (saveButton != null) {
            saveButton.active = nameByteLength(nameBox == null ? name : nameBox.getValue())
                    <= ProtocolConstants.NAME_MAX_BYTES;
        }
    }

    private void save() {
        if (!ClientAccess.canConfigure()) {
            return;
        }
        String value = nameBox.getValue();
        int bytes = nameByteLength(value);
        if (bytes > ProtocolConstants.NAME_MAX_BYTES) {
            feedback = Component.translatable("screen.network-socket.name_too_long",
                    String.valueOf(bytes), String.valueOf(ProtocolConstants.NAME_MAX_BYTES));
            feedbackSuccess = false;
            return;
        }
        if (value.indexOf(',') >= 0 || value.indexOf('=') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            feedback = Component.translatable("message.network-socket.name_invalid");
            feedbackSuccess = false;
            return;
        }
        ClientPlayNetworking.send(new SetBlockNameC2S(pos, value));
    }

    public void applyResult(BlockNameResultS2C result) {
        this.name = result.name();
        this.status = ConnectionStatus.byOrdinal(result.status());
        if (result.success()) {
            if (nameBox != null) {
                nameBox.setValue(name);
            }
            feedback = Component.translatable("message.network-socket.name_saved");
            feedbackSuccess = true;
        } else {
            feedback = Component.translatable("message.network-socket.save_failed");
            feedbackSuccess = false;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(extractor, mouseX, mouseY, partialTick);
        int x = panelX();
        int y = panelY();
        extractor.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xE6101010);
        extractor.fill(x, y, x + PANEL_WIDTH, y + 1, 0xFF808080);
        extractor.fill(x, y + PANEL_HEIGHT - 1, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xFF808080);
        extractor.fill(x, y, x + 1, y + PANEL_HEIGHT, 0xFF808080);
        extractor.fill(x + PANEL_WIDTH - 1, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xFF808080);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        int x = panelX();
        int y = panelY();

        extractor.centeredText(this.font, Component.translatable("screen.network-socket.block_title"),
                x + PANEL_WIDTH / 2, y + 12, 0xFFFFFFFF);
        extractor.text(this.font, Component.translatable("screen.network-socket.block_pos",
                pos.getX(), pos.getY(), pos.getZ()), x + 20, y + 32, 0xFFA0A0A0);
        extractor.text(this.font, Component.translatable("screen.network-socket.name"), x + 20, y + 45, 0xFFD0D0D0);
        String currentName = nameBox == null ? name : nameBox.getValue();
        int nameBytes = nameByteLength(currentName);
        extractor.text(this.font, Component.translatable("screen.network-socket.name_bytes",
                        String.valueOf(nameBytes), String.valueOf(ProtocolConstants.NAME_MAX_BYTES)),
                x + 20, y + 79,
                nameBytes > ProtocolConstants.NAME_MAX_BYTES ? 0xFFFF5555 : 0xFF909090);
        extractor.text(this.font, Component.translatable("screen.network-socket.status_value",
                        Component.translatable(status.translationKey())),
                x + 20, y + 94, statusColor(status));
        extractor.text(this.font, Component.translatable("screen.network-socket.name_hint"),
                x + 20, y + 110, 0xFF909090);
        if (feedback != null) {
            extractor.text(this.font, feedback, x + 20, y + 127,
                    feedbackSuccess ? 0xFF55FF55 : 0xFFFF5555);
        }
    }

    private int statusColor(ConnectionStatus value) {
        return switch (value) {
            case NOT_CONNECTED -> 0xFFB0B0B0;
            case CONNECTING -> 0xFFFFFF55;
            case CONNECTED -> 0xFF55FF55;
            case DISCONNECTED -> 0xFFFF5555;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
package ws.fabricmod.networksocket.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import ws.fabricmod.networksocket.config.ConnectionStatus;
import ws.fabricmod.networksocket.network.payload.ConfigDataS2C;
import ws.fabricmod.networksocket.network.payload.ConnectionEntryView;
import ws.fabricmod.networksocket.network.payload.RequestConfigC2S;
import ws.fabricmod.networksocket.network.payload.SaveConfigC2S;
import ws.fabricmod.networksocket.network.payload.TableS2C;
import ws.fabricmod.networksocket.network.payload.ToggleConnectionC2S;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tabbed configuration screen: UI1 (global network settings) and UI2 (name/status table).
 * Opened by the config keybinding; updates are pushed by the server.
 */
public class NetworkConfigScreen extends Screen {
    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = 224;
    private static final int ROWS_PER_PAGE = 5;

    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_LABEL = 0xFFE0E0E0;
    private static final int TEXT_MUTED = 0xFFA0A0A0;
    private static final int TEXT_HEADER = 0xFFC0C0C0;
    private static final int TEXT_ERROR = 0xFFFF5555;

    private enum Tab {
        GLOBAL,
        TABLE
    }

    private Tab tab = Tab.GLOBAL;
    private int page;

    private String addressText;
    private String portText;
    private String tokenText;
    private String maxText;
    private String keepaliveText;
    private String timeoutText;
    private List<ConnectionEntryView> entries = new ArrayList<>();
    private Component localError;

    private EditBox addressBox;
    private EditBox portBox;
    private EditBox tokenBox;
    private EditBox maxBox;
    private EditBox keepaliveBox;
    private EditBox timeoutBox;

    public NetworkConfigScreen(ConfigDataS2C data) {
        super(Component.translatable("screen.network-socket.config"));
        this.addressText = data.address();
        this.portText = Integer.toString(data.port());
        this.tokenText = data.token();
        this.maxText = Integer.toString(data.maxConnectCount());
        this.keepaliveText = Integer.toString(data.keepaliveTime());
        this.timeoutText = Integer.toString(data.timeout());
        this.entries = sorted(data.entries());
    }

    private static List<ConnectionEntryView> sorted(List<ConnectionEntryView> source) {
        List<ConnectionEntryView> copy = new ArrayList<>(source);
        copy.sort(Comparator.comparing(ConnectionEntryView::name));
        return copy;
    }

    private int panelX() {
        return Math.max(0, (width - panelWidth()) / 2);
    }

    private int panelY() {
        return Math.max(0, (height - panelHeight()) / 2);
    }

    private int panelWidth() {
        return Math.min(PANEL_WIDTH, Math.max(300, width - 8));
    }

    private int panelHeight() {
        return Math.min(PANEL_HEIGHT, Math.max(200, height - 8));
    }

    @Override
    protected void init() {
        int x = panelX();
        int y = panelY();

        addRenderableWidget(Button.builder(Component.translatable("screen.network-socket.tab.global"), button -> {
            collectFields();
            tab = Tab.GLOBAL;
            localError = null;
            rebuildWidgets();
        }).bounds(x + 10, y + 24, 110, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.network-socket.tab.table"), button -> {
            collectFields();
            tab = Tab.TABLE;
            localError = null;
            rebuildWidgets();
        }).bounds(x + 125, y + 24, 110, 20).build());

        if (tab == Tab.GLOBAL) {
            initGlobal(x, y);
        } else {
            initTable(x, y);
        }
    }

    private void initGlobal(int x, int y) {
        int fieldX = x + 115;
        int fieldWidth = panelWidth() - 125;
        addressBox = textField(fieldX, y + 48, fieldWidth, addressText, 255);
        portBox = textField(fieldX, y + 70, fieldWidth, portText, 5);
        tokenBox = textField(fieldX, y + 92, fieldWidth, tokenText, 256);
        maxBox = textField(fieldX, y + 114, fieldWidth, maxText, 5);
        keepaliveBox = textField(fieldX, y + 136, fieldWidth, keepaliveText, 6);
        timeoutBox = textField(fieldX, y + 158, fieldWidth, timeoutText, 5);

        addRenderableWidget(Button.builder(Component.translatable("screen.network-socket.save"), button -> save())
                .bounds(x + 10, y + panelHeight() - 24, 110, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.network-socket.close"), button -> onClose())
                .bounds(x + panelWidth() - 120, y + panelHeight() - 24, 110, 20).build());
    }

    private void initTable(int x, int y) {
        int start = page * ROWS_PER_PAGE;
        int end = Math.min(entries.size(), start + ROWS_PER_PAGE);
        for (int index = start; index < end; index++) {
            ConnectionEntryView entry = entries.get(index);
            int rowY = y + 62 + (index - start) * 22;
            addRenderableWidget(Button.builder(Component.translatable("screen.network-socket.connect"),
                    button -> toggle(entry.name(), true))
                    .bounds(x + panelWidth() - 145, rowY, 60, 18).build());
            addRenderableWidget(Button.builder(Component.translatable("screen.network-socket.disconnect"),
                    button -> toggle(entry.name(), false))
                    .bounds(x + panelWidth() - 80, rowY, 60, 18).build());
        }

        addRenderableWidget(Button.builder(Component.translatable("screen.network-socket.prev"), button -> {
            if (page > 0) {
                page--;
                rebuildWidgets();
            }
        }).bounds(x + 10, y + panelHeight() - 48, 45, 18).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.network-socket.next"), button -> {
            if ((page + 1) * ROWS_PER_PAGE < entries.size()) {
                page++;
                rebuildWidgets();
            }
        }).bounds(x + 60, y + panelHeight() - 48, 45, 18).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.network-socket.refresh"),
                        button -> refresh())
                .bounds(x + 110, y + panelHeight() - 48, 55, 18).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.network-socket.close"), button -> onClose())
                .bounds(x + panelWidth() - 120, y + panelHeight() - 24, 110, 20).build());
    }

    private EditBox textField(int x, int y, int width, String value, int maxLength) {
        EditBox box = new EditBox(this.font, x, y, width, 20, Component.empty());
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        addRenderableWidget(box);
        return box;
    }

    private void collectFields() {
        if (addressBox != null) {
            addressText = addressBox.getValue();
            portText = portBox.getValue();
            tokenText = tokenBox.getValue();
            maxText = maxBox.getValue();
            keepaliveText = keepaliveBox.getValue();
            timeoutText = timeoutBox.getValue();
        }
    }

    private void save() {
        if (!ClientAccess.canConfigure()) {
            return;
        }
        collectFields();
        int port;
        int max;
        int keepalive;
        int timeout;
        try {
            port = Integer.parseInt(portText.trim());
            max = Integer.parseInt(maxText.trim());
            keepalive = Integer.parseInt(keepaliveText.trim());
            timeout = Integer.parseInt(timeoutText.trim());
        } catch (NumberFormatException e) {
            localError = Component.translatable("screen.network-socket.invalid_number");
            return;
        }
        localError = null;
        ClientPlayNetworking.send(new SaveConfigC2S(addressText.trim(), port, tokenText,
                max, keepalive, timeout));
    }

    private void toggle(String name, boolean enable) {
        if (ClientAccess.canConfigure()) {
            ClientPlayNetworking.send(new ToggleConnectionC2S(name, enable));
        }
    }

    private void refresh() {
        if (ClientAccess.canConfigure()) {
            ClientPlayNetworking.send(RequestConfigC2S.INSTANCE);
        }
    }

    public void applyConfigData(ConfigDataS2C data) {
        this.addressText = data.address();
        this.portText = Integer.toString(data.port());
        this.tokenText = data.token();
        this.maxText = Integer.toString(data.maxConnectCount());
        this.keepaliveText = Integer.toString(data.keepaliveTime());
        this.timeoutText = Integer.toString(data.timeout());
        this.entries = sorted(data.entries());
        clampPage();
        if (tab == Tab.GLOBAL) {
            if (addressBox != null) {
                addressBox.setValue(addressText);
                portBox.setValue(portText);
                tokenBox.setValue(tokenText);
                maxBox.setValue(maxText);
                keepaliveBox.setValue(keepaliveText);
                timeoutBox.setValue(timeoutText);
            }
        } else {
            rebuildWidgets();
        }
    }

    public void applyTable(TableS2C data) {
        this.entries = sorted(data.entries());
        clampPage();
        if (tab == Tab.TABLE) {
            rebuildWidgets();
        }
    }

    private void clampPage() {
        int maxPage = Math.max(0, (entries.size() - 1) / ROWS_PER_PAGE);
        if (page > maxPage) {
            page = maxPage;
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(extractor, mouseX, mouseY, partialTick);
        int x = panelX();
        int y = panelY();
        int w = panelWidth();
        int h = panelHeight();
        extractor.fill(x, y, x + w, y + h, 0xE6101010);
        extractor.fill(x, y, x + w, y + 1, 0xFF808080);
        extractor.fill(x, y + h - 1, x + w, y + h, 0xFF808080);
        extractor.fill(x, y, x + 1, y + h, 0xFF808080);
        extractor.fill(x + w - 1, y, x + w, y + h, 0xFF808080);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor extractor, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick);
        int x = panelX();
        int y = panelY();

        extractor.centeredText(this.font, Component.translatable("screen.network-socket.title"),
                x + panelWidth() / 2, y + 8, TEXT_WHITE);

        if (tab == Tab.GLOBAL) {
            String[] labels = {
                    "screen.network-socket.address",
                    "screen.network-socket.port",
                    "screen.network-socket.token",
                    "screen.network-socket.max_connect",
                    "screen.network-socket.keepalive",
                    "screen.network-socket.timeout"
            };
            for (int i = 0; i < labels.length; i++) {
                extractor.text(this.font, Component.translatable(labels[i]),
                        x + 10, y + 53 + i * 22, TEXT_LABEL);
            }
            if (localError != null) {
                extractor.text(this.font, localError, x + 10, y + 181, TEXT_ERROR);
            } else {
                extractor.text(this.font, Component.translatable("screen.network-socket.notice1"),
                        x + 10, y + 181, TEXT_MUTED);
            }
        } else {
            int statusX = panelWidth() >= 380 ? 150 : 115;
            int nameWidth = statusX - 20;
            extractor.text(this.font, Component.translatable("screen.network-socket.name"),
                    x + 10, y + 51, TEXT_HEADER);
            extractor.text(this.font, Component.translatable("screen.network-socket.status"),
                    x + statusX, y + 51, TEXT_HEADER);

            if (entries.isEmpty()) {
                extractor.text(this.font, Component.translatable("screen.network-socket.empty"),
                        x + 10, y + 72, TEXT_MUTED);
            } else {
                int start = page * ROWS_PER_PAGE;
                int end = Math.min(entries.size(), start + ROWS_PER_PAGE);
                for (int index = start; index < end; index++) {
                    ConnectionEntryView entry = entries.get(index);
                    int rowY = y + 62 + (index - start) * 22;
                    ConnectionStatus status = ConnectionStatus.byOrdinal(entry.status());
                    extractor.text(this.font, truncated(entry.name(), nameWidth),
                            x + 10, rowY + 5, TEXT_WHITE);
                    extractor.text(this.font, Component.translatable(status.translationKey()),
                            x + statusX, rowY + 5, statusColor(status));
                }
            }

            int totalPages = Math.max(1, (entries.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
            extractor.text(this.font, Component.translatable("screen.network-socket.page",
                            page + 1, totalPages, entries.size()),
                    x + 175, y + 181, TEXT_HEADER);
        }
    }

    private String truncated(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - this.font.width("..."))) + "...";
    }

    private int statusColor(ConnectionStatus status) {
        return switch (status) {
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
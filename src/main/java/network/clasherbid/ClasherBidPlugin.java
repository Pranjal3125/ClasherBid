package network.clasherbid;

import net.milkbowl.vault.economy.Economy;
import network.clasherbid.auction.AuctionManager;
import network.clasherbid.command.AdminCommand;
import network.clasherbid.command.BidCommand;
import network.clasherbid.config.MessageService;
import network.clasherbid.config.PluginConfig;
import network.clasherbid.gui.AuctionGui;
import network.clasherbid.storage.HistoryStore;
import network.clasherbid.storage.LedgerStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * ClasherBid entry point. Wires configuration, MiniMessage localization,
 * Vault economy integration, crash-safe storage, GUI, and commands.
 */
public final class ClasherBidPlugin extends JavaPlugin {
    private Economy economy;
    private PluginConfig pluginConfig;
    private MessageService messages;
    private AuctionManager auctions;
    private AuctionGui gui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Let all plugins finish onEnable before checking Vault service registrations.
        getServer().getScheduler().runTask(this, this::initialize);
    }

    private void initialize() {
        if (!isEnabled()) return;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null || rsp.getProvider() == null) {
            getLogger().severe("No Vault economy provider found after plugin initialization. Disabling ClasherBid.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = rsp.getProvider();
        pluginConfig = new PluginConfig(this);
        messages = new MessageService(this, pluginConfig);
        LedgerStore ledger = new LedgerStore(getDataFolder(), getLogger());
        HistoryStore history = new HistoryStore(getDataFolder(), getLogger());

        auctions = new AuctionManager(this, economy, pluginConfig, messages, ledger, history);
        gui = new AuctionGui(this, economy, pluginConfig, messages, auctions);

        getServer().getPluginManager().registerEvents(auctions, this);
        getServer().getPluginManager().registerEvents(gui, this);

        PluginCommand bidCmd = getCommand("bid");
        PluginCommand adminCmd = getCommand("clasherbid");
        if (bidCmd == null || adminCmd == null) {
            getLogger().severe("Required commands are missing from plugin.yml. Disabling ClasherBid.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        BidCommand bidCommand = new BidCommand(economy, messages, auctions, gui);
        bidCmd.setExecutor(bidCommand);
        bidCmd.setTabCompleter(bidCommand);

        AdminCommand adminCommand = new AdminCommand(this, economy, messages, auctions);
        adminCmd.setExecutor(adminCommand);
        adminCmd.setTabCompleter(adminCommand);

        auctions.load();
        getLogger().info("ClasherBid v" + getPluginMeta().getVersion() + " enabled with Vault provider: " + economy.getName());
    }

    public void reloadConfiguration() {
        if (pluginConfig != null) pluginConfig.reload();
        if (messages != null) messages.reload();
    }

    @Override
    public void onDisable() {
        if (gui != null) gui.shutdown();
        if (auctions != null) auctions.shutdown();
    }

    public Economy economy() { return economy; }
    public PluginConfig pluginConfig() { return pluginConfig; }
    public MessageService messages() { return messages; }
    public AuctionManager auctions() { return auctions; }
}

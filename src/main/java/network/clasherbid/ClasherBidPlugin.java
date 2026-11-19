package network.clasherbid;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClasherBidPlugin extends JavaPlugin {
    private Economy economy;
    private AuctionManager auctions;
    private AuctionGui gui;

    @Override public void onEnable() {
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
        auctions = new AuctionManager(this, economy);
        gui = new AuctionGui(this, auctions);
        getServer().getPluginManager().registerEvents(auctions, this);
        getServer().getPluginManager().registerEvents(gui, this);
        if (getCommand("bid") == null || getCommand("claim") == null || getCommand("clasherbid") == null) {
            getLogger().severe("Required commands are missing from plugin.yml. Disabling ClasherBid.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("bid").setExecutor(gui);
        getCommand("claim").setExecutor(gui);
        AdminCommand adminCommand = new AdminCommand(this, auctions);
        getCommand("clasherbid").setExecutor(adminCommand);
        getCommand("clasherbid").setTabCompleter(adminCommand);
        auctions.load();
        getLogger().info("ClasherBid enabled with " + economy.getName());
    }

    @Override public void onDisable() {
        if (auctions != null) auctions.shutdown();
    }

    public Economy economy() { return economy; }
}

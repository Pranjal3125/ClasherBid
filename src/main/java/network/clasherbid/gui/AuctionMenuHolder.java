package network.clasherbid.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Secure InventoryHolder marker for ClasherBid GUIs.
 * Prevents spoofed chest titles from triggering auction click handlers.
 */
public final class AuctionMenuHolder implements InventoryHolder {
    public enum ViewType {
        LIVE_AUCTION,
        CONFIRM_BID,
        HISTORY
    }

    private final ViewType viewType;
    private Inventory inventory;

    public AuctionMenuHolder(ViewType viewType) {
        this.viewType = viewType;
    }

    public ViewType viewType() {
        return viewType;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}

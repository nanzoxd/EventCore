package com.eventcore.koth;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The only class that touches the WorldEdit API (FAWE implements the same API), so the plugin loads without either installed.
 * Call it from the region thread of the target location.
 */
final class WeBridge {
    private WeBridge() { }

    private static Clipboard read(File f) throws IOException {
        ClipboardFormat fmt = ClipboardFormats.findByFile(f);
        if (fmt == null) throw new IOException("unknown schematic format: " + f.getName());
        try (ClipboardReader r = fmt.getReader(new FileInputStream(f))) { return r.read(); }
    }

    /** Backs up the area first, then pastes (air included, so the site is cleared). Returns {minX,minY,minZ,maxX,maxY,maxZ}. */
    static int[] paste(File schem, World world, int cx, int y, int cz, File backup) throws Exception {
        Clipboard cb = read(schem);
        BlockVector3 dim = cb.getDimensions(), cbMin = cb.getMinimumPoint(), origin = cb.getOrigin();
        if (y + dim.getBlockY() > world.getMaxHeight() || y < world.getMinHeight()) throw new IllegalStateException("the build is " + dim.getBlockY() + " blocks tall and does not fit at this height");
        BlockVector3 min = BlockVector3.at(cx - dim.getBlockX() / 2, y, cz - dim.getBlockZ() / 2);
        BlockVector3 max = min.add(dim).subtract(BlockVector3.ONE);
        com.sk89q.worldedit.world.World w = BukkitAdapter.adapt(world);
        try (EditSession es = WorldEdit.getInstance().newEditSessionBuilder().world(w).maxBlocks(-1).build()) {
            CuboidRegion region = new CuboidRegion(w, min, max);
            BlockArrayClipboard old = new BlockArrayClipboard(region);
            ForwardExtentCopy copy = new ForwardExtentCopy(es, region, old, min);
            copy.setCopyingEntities(false);
            Operations.complete(copy);
            try (ClipboardWriter out = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(new FileOutputStream(backup))) { out.write(old); }
            Operation op = new ClipboardHolder(cb).createPaste(es).to(min.add(origin.subtract(cbMin))).ignoreAirBlocks(false).copyEntities(false).build();
            Operations.complete(op);
        }
        return new int[]{min.getBlockX(), min.getBlockY(), min.getBlockZ(), max.getBlockX(), max.getBlockY(), max.getBlockZ()};
    }

    static void restore(File backup, World world, int[] min) throws Exception {
        Clipboard cb = read(backup);
        BlockVector3 m = BlockVector3.at(min[0], min[1], min[2]);
        try (EditSession es = WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(world)).maxBlocks(-1).build()) {
            Operation op = new ClipboardHolder(cb).createPaste(es).to(m.add(cb.getOrigin().subtract(cb.getMinimumPoint()))).ignoreAirBlocks(false).copyEntities(false).build();
            Operations.complete(op);
        }
    }

    static int[] selection(Player p) {
        try {
            LocalSession s = WorldEdit.getInstance().getSessionManager().get(BukkitAdapter.adapt(p));
            Region r = s.getSelection(BukkitAdapter.adapt(p.getWorld()));
            BlockVector3 a = r.getMinimumPoint(), b = r.getMaximumPoint();
            return new int[]{a.getBlockX(), a.getBlockY(), a.getBlockZ(), b.getBlockX(), b.getBlockY(), b.getBlockZ()};
        } catch (IncompleteRegionException e) { return null; }
    }
}

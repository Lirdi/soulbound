package net.soulbound;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Stairs;

/**
 * Works on Moloch's pyramid, done once on the generated realm (it was carved by the terrain itself):
 *
 * The four stairways the terrain cut into the pyramid rose two blocks every two blocks, which nobody can walk up.
 * They're rebuilt as real stairs, one block up per step from the plaza (y 60) to the summit (y 132), banded red and
 * black like the pyramid, with braziers along both edges, obelisks at the foot and flame pillars at the top.
 * The east, south and west sides also get a paved causeway out to their own arrival point and Exit Gate.
 */
final class TempleWorks {
    /** The four sides, as the direction you walk away from the pyramid. North is -z, as in Minecraft. */
    enum Side {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);
        final int ox, oz;
        Side(int ox, int oz) { this.ox = ox; this.oz = oz; }
        /** The direction across the stairway (to the right when walking out). */
        int cx() { return -oz; }
        int cz() { return ox; }
        BlockFace inward() { return switch (this) { case NORTH -> BlockFace.SOUTH; case SOUTH -> BlockFace.NORTH; case EAST -> BlockFace.WEST; case WEST -> BlockFace.EAST; }; }
        /** Yaw for someone standing on this side, facing the pyramid. */
        float yawIn() { return switch (this) { case NORTH -> 0f; case EAST -> 90f; case SOUTH -> 180f; case WEST -> 270f; }; }
    }

    static final int FOOT = 92, TOP = 20, PLAZA_Y = 60, HALF = 4;

    private final World w;
    TempleWorks(World w) { this.w = w; }

    private Block at(Side s, int d, int c, int y) { return w.getBlockAt(s.ox * d + s.cx() * c, y, s.oz * d + s.cz() * c); }
    private static void set(Block b, BlockData d) { b.setBlockData(d, false); }
    private static BlockData m(Material mat) { return mat.createBlockData(); }

    /** Rebuild one stairway: proper stairs all the way up, braziers on the edges, obelisks and flames. */
    void stairs(Side s) {
        for (int d = FOOT - 1; d > TOP; d--) {
            int y = PLAZA_Y + (FOOT - d);
            boolean band = ((FOOT - d) / 2) % 2 == 0;
            boolean brazier = (FOOT - d) % 8 == 4;
            for (int c = -HALF; c <= HALF; c++) {
                Block step = at(s, d, c, y);
                for (int up = 1; up <= 3; up++) { Block a = at(s, d, c, y + up); if (!a.isEmpty() && !a.getType().name().contains("FIRE")) set(a, m(Material.AIR)); }
                Block below = at(s, d, c, y - 1);
                if (!below.getType().isSolid()) set(below, m(band ? Material.RED_NETHER_BRICKS : Material.POLISHED_BLACKSTONE_BRICKS));
                if (brazier && Math.abs(c) == HALF) {
                    set(step, m(Material.CHISELED_POLISHED_BLACKSTONE));
                    BlockData fire = m(Material.CAMPFIRE);
                    if (fire instanceof Lightable l) l.setLit(true);
                    set(at(s, d, c, y + 1), fire);
                    continue;
                }
                Stairs st = (Stairs) m(band ? Material.RED_NETHER_BRICK_STAIRS : Material.POLISHED_BLACKSTONE_BRICK_STAIRS);
                st.setFacing(s.inward());
                st.setHalf(org.bukkit.block.data.Bisected.Half.BOTTOM);
                set(step, st);
            }
        }
        // obelisks either side of the foot: polished blackstone, a gold band, a fire on top
        for (int c : new int[]{-(HALF + 2), HALF + 2}) {
            for (int y = PLAZA_Y + 1; y <= PLAZA_Y + 7; y++) set(at(s, FOOT + 2, c, y), m(y == PLAZA_Y + 5 ? Material.GOLD_BLOCK : Material.POLISHED_BLACKSTONE_BRICKS));
            set(at(s, FOOT + 2, c, PLAZA_Y + 8), m(Material.GILDED_BLACKSTONE));
            BlockData fire = m(Material.SOUL_CAMPFIRE);
            if (fire instanceof Lightable l) l.setLit(true);
            set(at(s, FOOT + 2, c, PLAZA_Y + 9), fire);
        }
        // flame pillars where the stairway meets the summit
        int topY = PLAZA_Y + (FOOT - TOP);
        for (int c : new int[]{-(HALF + 1), HALF + 1}) {
            for (int y = topY + 1; y <= topY + 3; y++) set(at(s, TOP, c, y), m(Material.POLISHED_BLACKSTONE_BRICKS));
            set(at(s, TOP, c, topY + 4), m(Material.MAGMA_BLOCK));
            BlockData fire = m(Material.CAMPFIRE);
            if (fire instanceof Lightable l) l.setLit(true);
            set(at(s, TOP, c, topY + 5), fire);
        }
    }

    /** A paved causeway from the foot of the stairs out to a side's arrival point and gate, over whatever lies between. */
    void causeway(Side s, int from, int to) {
        for (int d = from; d <= to; d++) {
            for (int c = -3; c <= 3; c++) {
                boolean edge = Math.abs(c) == 3;
                set(at(s, d, c, PLAZA_Y), m(edge ? Material.CHISELED_POLISHED_BLACKSTONE : (d % 4 == 0 ? Material.RED_NETHER_BRICKS : Material.POLISHED_BLACKSTONE_BRICKS)));
                for (int y = PLAZA_Y - 1; y >= PLAZA_Y - 6; y--) {
                    Block b = at(s, d, c, y);
                    if (b.getType().isSolid()) break;
                    set(b, m(Material.POLISHED_BLACKSTONE));
                }
                for (int y = PLAZA_Y + 1; y <= PLAZA_Y + 5; y++) {
                    Block b = at(s, d, c, y);
                    if (!b.isEmpty()) set(b, m(Material.AIR));
                }
                if (edge) {
                    set(at(s, d, c, PLAZA_Y + 1), m(Material.POLISHED_BLACKSTONE_BRICK_WALL));
                    if (d % 6 == 0) {
                        BlockData fire = m(Material.SOUL_LANTERN);
                        set(at(s, d, c, PLAZA_Y + 2), fire);
                    }
                }
            }
        }
    }

    /** Where a side's arrival point stands, and its gate behind it. */
    static int[] arrival(Side s) { return new int[]{s.ox * 113, s.oz * 113}; }
    static int[] gate(Side s) { return new int[]{s.ox * 120, s.oz * 120}; }
}

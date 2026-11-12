package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.graphics.SurfaceTexture;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Where the player said it hangs its layer, as the frame save asks for it. The players and parent
 * layers here are stand-ins: a SurfaceControl needs a compositor Robolectric doesn't have, so a
 * parent is any object, named the way SurfaceControl names itself.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlayerLayersTest {
    private final List<Player> players = new ArrayList<>();

    @After public void tearDown() {
        for (Player player : players) {
            PlayerLayers.detached(player);
            player.surface.release();
        }
    }

    private Player player() {
        Player player = new Player(new Surface(new SurfaceTexture(0)));
        players.add(player);
        return player;
    }

    @Test public void aLayerIsFoundUnderTheParentItWasHungUnderWithItsBufferSize() {
        Parent cell = new Parent("91fe2b6 SurfaceView[MainActivity]@0", 1);
        Player player = player();

        PlayerLayers.reparented(player, cell, 576, 1024, true);

        PlayerLayers.Layer layer = PlayerLayers.placedUnder(cell);
        assertNotNull(layer);
        assertSame(player.surface, layer.surface);
        assertEquals(576, layer.width);
        assertEquals(1024, layer.height);
        assertNull("another cell's view has no layer of this player's",
                PlayerLayers.placedUnder(new Parent("51fc295 SurfaceView[MainActivity]@0", 2)));
        assertNull(PlayerLayers.placedUnder(null));
    }

    @Test public void aSecondJavaObjectOnTheSameLayerFindsItByName() {
        Player player = player();
        PlayerLayers.reparented(player, new Parent("91fe2b6 SurfaceView[MainActivity]@0", 1), 576, 1024, true);

        assertSame(player.surface,
                PlayerLayers.placedUnder(new Parent("91fe2b6 SurfaceView[MainActivity]@0", 7)).surface);
        assertNull("a parent with no layer name matches only itself",
                PlayerLayers.placedUnder(new Object()));
    }

    @Test public void theNewestShowingLayerWinsAndAHiddenOneOnlyWhenNothingShows() {
        Parent cell = new Parent("91fe2b6 SurfaceView[MainActivity]@0", 1);
        Player warm = player();
        Player shown = player();
        Player older = player();

        PlayerLayers.reparented(older, cell, 720, 1280, true);
        PlayerLayers.reparented(shown, cell, 576, 1024, true);
        PlayerLayers.reparented(warm, cell, 540, 960, false);
        assertSame("a hidden layer isn't the picture on screen", shown.surface, PlayerLayers.placedUnder(cell).surface);

        PlayerLayers.detached(shown);
        PlayerLayers.detached(older);
        assertSame("only the hidden one is left", warm.surface, PlayerLayers.placedUnder(cell).surface);
    }

    @Test public void aLayerTakenDownOrMovedElsewhereIsGoneFromItsOldParent() {
        Parent first = new Parent("91fe2b6 SurfaceView[MainActivity]@0", 1);
        Parent second = new Parent("51fc295 SurfaceView[MainActivity]@0", 2);
        Player player = player();

        PlayerLayers.reparented(player, first, 576, 1024, true);
        PlayerLayers.reparented(player, second, 576, 1024, true);
        assertNull(PlayerLayers.placedUnder(first));
        assertSame(player.surface, PlayerLayers.placedUnder(second).surface);

        PlayerLayers.detached(player);
        assertNull(PlayerLayers.placedUnder(second));
    }

    @Test public void aReparentWithNoParentResizesOrHidesTheLayerWhereItHangs() {
        Parent cell = new Parent("91fe2b6 SurfaceView[MainActivity]@0", 1);
        Player player = player();
        PlayerLayers.reparented(player, cell, 576, 1024, true);

        PlayerLayers.reparented(player, null, 720, 1280, true);
        PlayerLayers.Layer layer = PlayerLayers.placedUnder(cell);
        assertEquals(720, layer.width);
        assertEquals(1280, layer.height);

        Player other = player();
        PlayerLayers.reparented(other, cell, 540, 960, true);
        PlayerLayers.reparented(other, null, 540, 960, false);
        assertSame("a layer hidden in place gives way to the one showing", player.surface,
                PlayerLayers.placedUnder(cell).surface);
    }

    @Test public void theAndroid13ReparentAlwaysShowsTheLayer() {
        Parent cell = new Parent("91fe2b6 SurfaceView[MainActivity]@0", 1);
        Player hidden = player();
        Player shown = player();
        PlayerLayers.reparented(hidden, cell, 576, 1024, false);
        PlayerLayers.shown(shown, cell, 576, 1024);
        PlayerLayers.reparented(hidden, null, 576, 1024, false);

        assertSame(shown.surface, PlayerLayers.placedUnder(cell).surface);
    }

    @Test public void aReleasedSurfaceIsntRead() {
        Parent cell = new Parent("91fe2b6 SurfaceView[MainActivity]@0", 1);
        Player player = player();
        PlayerLayers.reparented(player, cell, 576, 1024, true);

        player.surface.release();

        assertNull(PlayerLayers.placedUnder(cell));
    }

    @Test public void onlyAPlayerWithASurfaceCounts() {
        Parent cell = new Parent("91fe2b6 SurfaceView[MainActivity]@0", 1);
        Player empty = new Player(null);
        PlayerLayers.reparented(empty, cell, 576, 1024, true);
        PlayerLayers.reparented(null, cell, 576, 1024, true);
        PlayerLayers.reparented(player(), null, 576, 1024, true);

        assertNull(PlayerLayers.placedUnder(cell));
        PlayerLayers.detached(empty);
    }

    @Test public void theListStaysBounded() {
        Parent cell = new Parent("91fe2b6 SurfaceView[MainActivity]@0", 1);
        Player first = player();
        PlayerLayers.reparented(first, cell, 576, 1024, true);
        for (int i = 0; i < PlayerLayers.MAX_PLACED; i++) {
            PlayerLayers.reparented(player(), new Parent("cell " + i + " SurfaceView@0", 10 + i), 576, 1024, true);
        }

        assertNull("the oldest placement made room", PlayerLayers.placedUnder(cell));
    }

    @Test public void theLivePlayersLayerIsFoundUnderTheLiveCellUntilANullParentTakesItDown() {
        Parent cell = new Parent("de056db SurfaceView[MainActivity]@0", 1);
        Parent next = new Parent("9ed6bd SurfaceView[MainActivity]@0", 2);
        Surface stream = player().surface;

        PlayerLayers.live(cell, stream, 1080, 1920);
        PlayerLayers.Layer layer = PlayerLayers.placedUnder(cell);
        assertSame(stream, layer.surface);
        assertEquals(1080, layer.width);
        assertEquals(1920, layer.height);

        PlayerLayers.live(next, stream, 1080, 1920);
        assertNull("the stream moved to the next LIVE cell", PlayerLayers.placedUnder(cell));
        assertSame(stream, PlayerLayers.placedUnder(next).surface);

        PlayerLayers.live(null, stream, 0, 0);
        assertNull(PlayerLayers.placedUnder(next));
    }

    @Test public void aLayerNameIsReadOnlyFromSurfaceControlsOwnShape() {
        assertEquals("91fe2b6 SurfaceView[com.zhiliaoapp.musically/MainActivity]@0",
                PlayerLayers.layerName("Surface(name=91fe2b6 SurfaceView[com.zhiliaoapp.musically/MainActivity]@0)/@0x5c1d3"));
        assertNull(PlayerLayers.layerName("Surface(name=)/@0x1"));
        assertNull(PlayerLayers.layerName("something else"));
        assertNull(PlayerLayers.layerName(null));
    }

    /** TTPlayerSurface as far as the registry reads it: the Surface the player draws into. */
    public static final class Player {
        private final Surface surface;

        Player(Surface surface) {
            this.surface = surface;
        }

        public Surface getSurface() {
            return surface;
        }
    }

    /** A parent layer, named the way SurfaceControl prints itself. */
    private static final class Parent {
        private final String name;
        private final int id;

        Parent(String name, int id) {
            this.name = name;
            this.id = id;
        }

        @Override public String toString() {
            return "Surface(name=" + name + ")/@0x" + Integer.toHexString(id);
        }
    }
}

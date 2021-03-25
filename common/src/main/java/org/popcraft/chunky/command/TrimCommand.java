package org.popcraft.chunky.command;

import org.popcraft.chunky.Chunky;
import org.popcraft.chunky.Selection;
import org.popcraft.chunky.platform.Sender;
import org.popcraft.chunky.platform.World;
import org.popcraft.chunky.shape.Shape;
import org.popcraft.chunky.shape.ShapeFactory;
import org.popcraft.chunky.util.Coordinate;
import org.popcraft.chunky.util.Formatting;
import org.popcraft.chunky.util.Input;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.popcraft.chunky.Chunky.translate;

public class TrimCommand extends ChunkyCommand {
    public TrimCommand(Chunky chunky) {
        super(chunky);
    }

    @Override
    public void execute(Sender sender, String[] args) {
        if (args.length > 1) {
            Optional<World> world = Input.tryWorld(chunky, args[1]);
            if (world.isPresent()) {
                chunky.getSelection().world(world.get());
            } else {
                sender.sendMessage("help_delete");
                return;
            }
        }
        if (args.length > 2) {
            Optional<String> shape = Input.tryShape(args[2]);
            if (shape.isPresent()) {
                chunky.getSelection().shape(shape.get());
            } else {
                sender.sendMessage("help_delete");
                return;
            }
        }
        if (args.length > 3) {
            Optional<Double> centerX = Input.tryDoubleSuffixed(args[3]).filter(cx -> !Input.isPastWorldLimit(cx));
            Optional<Double> centerZ = Input.tryDoubleSuffixed(args.length > 4 ? args[4] : null).filter(cz -> !Input.isPastWorldLimit(cz));
            if (centerX.isPresent() && centerZ.isPresent()) {
                chunky.getSelection().center(centerX.get().intValue(), centerZ.get().intValue());
            } else {
                sender.sendMessage("help_delete");
                return;
            }
        }
        if (args.length > 5) {
            Optional<Integer> radiusX = Input.tryIntegerSuffixed(args[5]).filter(rx -> rx >= 0 && !Input.isPastWorldLimit(rx));
            if (radiusX.isPresent()) {
                chunky.getSelection().radius(radiusX.get());
            } else {
                sender.sendMessage("help_delete");
                return;
            }
        }
        if (args.length > 6) {
            Optional<Integer> radiusZ = Input.tryIntegerSuffixed(args[6]).filter(rz -> rz >= 0 && !Input.isPastWorldLimit(rz));
            if (radiusZ.isPresent()) {
                chunky.getSelection().radiusZ(radiusZ.get());
            } else {
                sender.sendMessage("help_delete");
                return;
            }
        }
        final Selection selection = chunky.getSelection().build();
        final Shape shape = ShapeFactory.getShape(selection);
        final Runnable deletionAction = () -> chunky.getPlatform().getServer().getScheduler().runTaskAsync(() -> {
            sender.sendMessage("format_start", translate("prefix"), selection.world().getName(), selection.centerX(), selection.centerZ(), Formatting.radius(selection.radiusX(), selection.radiusZ()));
            final Optional<Path> regionPath = selection.world().getRegionDirectory();
            final AtomicLong deleted = new AtomicLong();
            final long startTime = System.currentTimeMillis();
            if (regionPath.isPresent()) {
                try {
                    Files.walk(regionPath.get()).forEach(region -> deleted.getAndAdd(checkRegion(region, shape)));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            final long totalTime = System.currentTimeMillis() - startTime;
            sender.sendMessage("task_delete", translate("prefix"), deleted.get(), selection.world().getName(), totalTime / 1e3f);
        });
        chunky.setPendingAction(sender, deletionAction);
        sender.sendMessage("format_delete_confirm", translate("prefix"), selection.world().getName(), selection.shape(), selection.centerX(), selection.centerZ(), Formatting.radius(selection.radiusX(), selection.radiusZ()));
    }

    private int checkRegion(final Path region, final Shape shape) {
        Optional<Coordinate> regionCoordinate = tryRegionCoordinate(region);
        if (!regionCoordinate.isPresent()) {
            return 0;
        }
        int chunkX = regionCoordinate.get().getX() << 5;
        int chunkZ = regionCoordinate.get().getZ() << 5;
        if (shouldDeleteRegion(shape, chunkX, chunkZ)) {
            return deleteRegion(region);
        } else {
            return trimRegion(region, shape, chunkX, chunkZ);
        }
    }

    private Optional<Coordinate> tryRegionCoordinate(final Path region) {
        final String fileName = region.getFileName().toString();
        if (fileName == null || !fileName.startsWith("r.")) {
            return Optional.empty();
        }
        final int extension = fileName.indexOf(".mca");
        if (extension < 2) {
            return Optional.empty();
        }
        final String regionCoordinates = fileName.substring(2, extension);
        final int separator = regionCoordinates.indexOf('.');
        Optional<Integer> regionX = Input.tryInteger(regionCoordinates.substring(0, separator));
        Optional<Integer> regionZ = Input.tryInteger(regionCoordinates.substring(separator + 1));
        if (regionX.isPresent() && regionZ.isPresent()) {
            return Optional.of(new Coordinate(regionX.get(), regionZ.get()));
        }
        return Optional.empty();
    }

    private boolean shouldDeleteRegion(final Shape shape, final int chunkX, final int chunkZ) {
        for (int offsetX = 0; offsetX < 32; ++offsetX) {
            for (int offsetZ = 0; offsetZ < 32; ++offsetZ) {
                int chunkCenterX = ((chunkX + offsetX) << 4) + 8;
                int chunkCenterZ = ((chunkZ + offsetZ) << 4) + 8;
                if (shape.isBounding(chunkCenterX, chunkCenterZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int deleteRegion(final Path region) {
        try {
            Files.deleteIfExists(region);
            return 1024;
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        }
    }

    private int trimRegion(final Path region, final Shape shape, final int chunkX, final int chunkZ) {
        int deleted = 0;
        try (RandomAccessFile regionFile = new RandomAccessFile(region.toFile(), "rw")) {
            for (int offsetX = 0; offsetX < 32; ++offsetX) {
                for (int offsetZ = 0; offsetZ < 32; ++offsetZ) {
                    int chunkCenterX = ((chunkX + offsetX) << 4) + 8;
                    int chunkCenterZ = ((chunkZ + offsetZ) << 4) + 8;
                    if (!shape.isBounding(chunkCenterX, chunkCenterZ)) {
                        int chunkLocation = ((offsetX % 32) + (offsetZ % 32) * 32) * 4;
                        regionFile.seek(chunkLocation);
                        if (regionFile.readInt() != 0) {
                            regionFile.seek(chunkLocation);
                            regionFile.writeInt(0);
                            ++deleted;
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return deleted;
    }

    @Override
    public List<String> tabSuggestions(Sender sender, String[] args) {
        if (args.length == 2) {
            List<String> suggestions = new ArrayList<>();
            chunky.getPlatform().getServer().getWorlds().forEach(world -> suggestions.add(world.getName()));
            return suggestions;
        } else if (args.length == 3) {
            return Input.SHAPES;
        }
        return Collections.emptyList();
    }
}
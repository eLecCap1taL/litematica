package litematica.util;

import java.nio.file.Path;

import malilib.util.FileUtils;

public class DefaultDirectories
{
    public static Path getDefaultSchematicDirectory()
    {
        return FileUtils.getMinecraftDirectory().resolve("schematics");
    }
}
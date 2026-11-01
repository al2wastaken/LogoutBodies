package io.github.logoutbodies.platform;

import io.github.logoutbodies.core.BodyFactory;
import io.github.logoutbodies.core.LogoutBodiesPlugin;

public final class PlatformPlugin extends LogoutBodiesPlugin {
    @Override
    protected BodyFactory createFactory() {
        return new NmsBodyFactory(this);
    }

    @Override
    protected String minecraftVersion() {
        return getPluginMeta().getDescription().substring("Minecraft ".length());
    }
}

package me.mrdaniel.adventuremmo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.api.Game;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.GameReloadEvent;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.format.TextColors;

import me.mrdaniel.adventuremmo.catalogtypes.abilities.Abilities;
import me.mrdaniel.adventuremmo.catalogtypes.abilities.Ability;
import me.mrdaniel.adventuremmo.catalogtypes.abilities.AbilityRegistryModule;
import me.mrdaniel.adventuremmo.catalogtypes.settings.Setting;
import me.mrdaniel.adventuremmo.catalogtypes.settings.SettingRegistryModule;
import me.mrdaniel.adventuremmo.catalogtypes.skills.SkillType;
import me.mrdaniel.adventuremmo.catalogtypes.skills.SkillTypeRegistryModule;
import me.mrdaniel.adventuremmo.catalogtypes.skills.SkillTypes;
import me.mrdaniel.adventuremmo.catalogtypes.tools.ToolType;
import me.mrdaniel.adventuremmo.catalogtypes.tools.ToolTypeRegistryModule;
import me.mrdaniel.adventuremmo.commands.CommandSettings;
import me.mrdaniel.adventuremmo.commands.CommandSkill;
import me.mrdaniel.adventuremmo.commands.CommandSkills;
import me.mrdaniel.adventuremmo.commands.CommandTop;
import me.mrdaniel.adventuremmo.data.manipulators.ImmutableMMOData;
import me.mrdaniel.adventuremmo.data.manipulators.ImmutableSuperToolData;
import me.mrdaniel.adventuremmo.data.manipulators.MMOData;
import me.mrdaniel.adventuremmo.data.manipulators.MMODataBuilder;
import me.mrdaniel.adventuremmo.data.manipulators.SuperToolData;
import me.mrdaniel.adventuremmo.data.manipulators.SuperToolDataBuilder;
import me.mrdaniel.adventuremmo.exception.ServiceException;
import me.mrdaniel.adventuremmo.io.Config;
import me.mrdaniel.adventuremmo.io.HoconPlayerDatabase;
import me.mrdaniel.adventuremmo.io.ItemDatabase;
import me.mrdaniel.adventuremmo.io.PlayerDatabase;
import me.mrdaniel.adventuremmo.listeners.AbilitiesListener;
import me.mrdaniel.adventuremmo.listeners.ClientListener;
import me.mrdaniel.adventuremmo.listeners.EconomyListener;
import me.mrdaniel.adventuremmo.listeners.WorldListener;
import me.mrdaniel.adventuremmo.managers.DoubleDropManager;
import me.mrdaniel.adventuremmo.managers.MenuManager;
import me.mrdaniel.adventuremmo.managers.MessageManager;
import me.mrdaniel.adventuremmo.managers.SuperToolManager;
import me.mrdaniel.adventuremmo.managers.TopManager;
import me.mrdaniel.adventuremmo.utils.ChoiceMaps;

@Plugin(id = "adventuremmo",
	name = "AdventureMMO",
	version = "2.0.1",
	description = "A light-weight plugin that adds skills with all sorts of fun game mechanics to your server.",
	authors = {"Daniel12321"})
public class AdventureMMO {

	private final Game game;
	private final Logger logger;
	private final Path configdir;
	private final PluginContainer container;

	private PlayerDatabase playerdata;
	private ItemDatabase itemdata;
	private MenuManager menus;
	private MessageManager messages;
	private TopManager tops;
	private DoubleDropManager doubledrops;
	private SuperToolManager supertools;
	private ChoiceMaps choices;

	@Inject
	public AdventureMMO(final Game game, @ConfigDir(sharedRoot = false) final Path path, final PluginContainer container) {
		this.game = game;
		this.logger = LoggerFactory.getLogger("AdventureMMO");
		this.configdir = path;
		this.container = container;

		if (!Files.exists(path)) {
			try { Files.createDirectory(path); }
			catch (final IOException exc) { this.logger.error("Failed to create main config directory: {}", exc); }
		}
	}

	@Listener
	public void onPreInit(@Nullable final GamePreInitializationEvent e) {
		this.logger.info("Registering custom data...");

		this.game.getDataManager().register(MMOData.class, ImmutableMMOData.class, new MMODataBuilder());
		this.game.getDataManager().register(SuperToolData.class, ImmutableSuperToolData.class, new SuperToolDataBuilder());

		this.game.getRegistry().registerModule(SkillType.class, new SkillTypeRegistryModule());
		this.game.getRegistry().registerModule(ToolType.class, new ToolTypeRegistryModule());
		this.game.getRegistry().registerModule(Ability.class, new AbilityRegistryModule());
		this.game.getRegistry().registerModule(Setting.class, new SettingRegistryModule());

		this.logger.info("Registered custom data successfully.");
	}

	@Listener
	public void onInit(@Nullable final GameInitializationEvent e) {
		this.logger.info("Loading plugin...");

		final long startuptime = System.currentTimeMillis();

		// Loading Config
		final Config config = new Config(this, this.configdir.resolve("config.conf"));

		// Registering Config Settings
		SkillTypes.VALUES.removeIf(skill -> !config.getNode("skills", skill.getId(), "enabled").getBoolean(true));
		Abilities.VALUES.forEach(a -> a.setValues(config.getNode("abilities", a.getId())));

		// Initializing Managers
		this.playerdata = new HoconPlayerDatabase(this, this.configdir.resolve("playerdata"));
		this.itemdata = new ItemDatabase(this, this.configdir.resolve("itemdata.conf"));
		this.menus = new MenuManager(this);
		this.messages = new MessageManager(this, config.getNode("messages"));
		this.tops = new TopManager(this, this.configdir.resolve("tops.conf"));
		this.doubledrops = new DoubleDropManager(this);
		this.supertools = new SuperToolManager(this);
		this.choices = new ChoiceMaps();

		// Registering Commands
		this.game.getCommandManager().register(this, CommandSpec.builder()
				.description(Text.of(TextColors.BLUE, "AdventureMMO | Skills Command"))
				.arguments(GenericArguments.optionalWeak(GenericArguments.choices(Text.of("skill"), this.choices.getSkills())))
				.executor(new CommandSkills(this))
				.build(), "skill", "skills", "mmoskill", "mmoskills");

		this.game.getCommandManager().register(this, CommandSpec.builder()
				.description(Text.of(TextColors.BLUE, "AdventureMMO | Top Command"))
				.arguments(GenericArguments.optionalWeak(GenericArguments.choices(Text.of("skill"), this.choices.getSkills())))
				.executor(new CommandTop(this))
				.build(), "mmotop", "skilltop");

		this.game.getCommandManager().register(this, CommandSpec.builder()
				.description(Text.of(TextColors.BLUE, "AdventureMMO | Settings Command"))
				.executor(new CommandSettings(this))
				.build(), "setting", "settings", "mmosetting", "mmosettings");

		SkillTypes.VALUES.forEach(skill -> {
			if (config.getNode("skills", skill.getId(), "short_command").getBoolean(true)) {
				this.game.getCommandManager().register(this, CommandSpec.builder()
						.description(Text.of(TextColors.BLUE, "AdventureMMO | ", skill.getName(), " Command"))
						.executor(new CommandSkill(this, skill))
						.build(), skill.getId());
			}
		});

		// Registering Listeners
		SkillTypes.VALUES.forEach(skill -> this.game.getEventManager().registerListeners(this, skill.getListener().apply(this, config)));
		this.game.getEventManager().registerListeners(this, new ClientListener(this));
		this.game.getEventManager().registerListeners(this, new AbilitiesListener(this, config));
		this.game.getEventManager().registerListeners(this, new WorldListener(this));
		if (config.getNode("economy", "enabled").getBoolean()) {
			try { this.game.getEventManager().registerListeners(this, new EconomyListener(this, config)); }
			catch (final ServiceException exc) { this.logger.error("No Economy Service was found! Install one or disable economy in the config file: {}", exc); }
		}
		this.logger.info("Loaded plugin successfully in {} milliseconds.", System.currentTimeMillis() - startuptime);
	}

	@Listener
	public void onReload(@Nullable final GameReloadEvent e) {
		this.logger.info("Reloading...");

		this.game.getEventManager().unregisterPluginListeners(this);
		this.game.getScheduler().getScheduledTasks(this).forEach(task -> task.cancel());
		this.game.getCommandManager().getOwnedBy(this).forEach(this.game.getCommandManager()::removeMapping);

		this.onInit(null);

		this.game.getServer().getOnlinePlayers().forEach(p -> this.playerdata.load(p.getUniqueId()));

		this.logger.info("Reloaded successfully.");
	}

	@Nonnull public Game getGame() { return this.game; }
	@Nonnull public Logger getLogger() { return this.logger; }
	@Nonnull public PluginContainer getContainer() { return this.container; }

	@Nonnull public PlayerDatabase getPlayerDatabase() { return this.playerdata; }
	@Nonnull public ItemDatabase getItemDatabase() { return this.itemdata; }
	@Nonnull public MenuManager getMenus() { return this.menus; }
	@Nonnull public MessageManager getMessages() { return this.messages; }
	@Nonnull public TopManager getTops() { return this.tops; }
	@Nonnull public DoubleDropManager getDoubleDrops() { return this.doubledrops; }
	@Nonnull public SuperToolManager getSuperTools() { return this.supertools; }
	@Nonnull public ChoiceMaps getChoices() { return this.choices; }
}
import static arc.Core.settings;
import static mindustry.Vars.content;

import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Timer;
import mindustry.core.NetClient;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.type.UnitType;

import util.Players;


public class PluginMain extends mindustry.mod.Plugin {
	private static ObjectMap<Team, Integer> stock = new ObjectMap<>();
	private static ObjectMap<Team, Boolean> inVote = new ObjectMap<>();
	private static ObjectMap<Team, Integer> votes = new ObjectMap<>();
	private static ObjectMap<Team, Votes> sessions = new ObjectMap<>();
	private static ObjectMap<Team, Votes> cooldowns = new ObjectMap<>();
	private static ObjectMap<Team, UnitType> unit = new ObjectMap<>();
	private static Seq<String> voted = new Seq<>();
	private static double ratio = 0.6;
	private static int time = 30, duration = 2, cooldown = 5;
	private static boolean isActivated = true, timer = true;
	
	//Called after all plugins have been created and commands have been registered
	public void init() {
		//init content for all teams
		for (Team team : Team.baseTeams) {
			stock.put(team, 0);
			inVote.put(team, false); 
			votes.put(team, 0);
		}
		if (!unit.isEmpty()) unit.clear();
		if (!voted.isEmpty()) voted.clear();
		sessions.forEach(s -> s.value.stopVotes());
		if (!sessions.isEmpty()) sessions.clear();
	}
	
    public PluginMain() {
    	loadSettings(); //check if have a save for active or not clients commands and other settings

    	Events.on(EventType.WorldLoadEvent.class, e -> startTimer()); //restart the timer during a new card
    	Events.on(EventType.GameOverEvent.class, e -> {
    		init();//re-init content team
    		timer = false; //stop the timer
    	}); 
    }
	
	//register commands that run on the server
	@Override
	public void registerServerCommands(CommandHandler handler){
		handler.register("unitsforall", "[on|off]", "Active/D�sactive le plugin (red�marrage du serveur n�cessaire)", arg -> {
			if (arg.length == 1) {
				boolean last = isActivated;
				switch (arg[0]) {
					case "on": case "true":
						isActivated = true;
						Log.info("Plugin activ�.");
						break;
					case "off": case "false":
						isActivated = false;
						Log.info("Plugin d�sactiv�.");
						break;
					default:
						Log.err("Arguments invalides !");
				}
				saveSettings();
				if (last != isActivated) Log.info("Des changements au niveau du plugin ont �t� apport�. Veuillez red�marrer le serveur pour que cela prenne effet.");
				
			} else Log.info("Le plugin est actuellement " + (isActivated ? "activ�" : "d�sactiv�"));
		});
	}
	    
	//register commands that player can invoke in-game
	@Override
	public void registerClientCommands(CommandHandler handler){
		if (isActivated) {
			handler.<Player>register("votespawn", "<oui|non>", "Donner son avis si le spawn doit avoir lieu ou pas", (arg, player) -> {
				if (!inVote.get(player.team())) {
					Players.err(player, "Aucun vote n'a �t� lanc� !");
					return;
				}
				if (voted.contains(player.uuid())) {
					Players.err(player, "Vous avez d�j� vot� !");
					return;
				}
				
				boolean accept;
				switch (arg[0]) {
					case "yes": case "y": case "oui": case "o":
						votes.put(player.team(), votes.get(player.team())+1);
						accept = true;
						break;
					case "no": case "n": case "non":
						votes.put(player.team(), votes.get(player.team())-1);
						accept = false;
						break;
					
					default:
						Players.err(player, "Arguments invalides !");
						return;
				}
				
				voted.add(player.uuid());
				Seq<Player> list = new Seq<>();
				Groups.player.each(p -> {
					if (p.team().equals(player.team())) list.add(p);
				});
				int rest = arc.math.Mathf.ceil((float) ratio * list.size);
				
				Players.messageToTeam(player.team(), "%s[orange] a vot� %s le spawn de [white]%s %s [lightgray](%s votes manquants)", 
					NetClient.colorizeName(player.id, player.name), accept ? "[green]pour[]" : "[scarlet]contre[]", stock, 
					unit.get(player.team()).name, rest-votes.get(player.team()));

				if (votes.get(player.team()) < rest) return;
				Players.messageToTeam(player.team(), "[green]Vote termin�. Les unit�s vont spawn � cot� d'un noyau.");
				startSpawn(player.team(), unit.get(player.team()));
				cooldowns.put(player.team(), new Votes(player.team(), true));
			});
			
			handler.<Player>register("spawnunit", "<unit�>", "Lance un vote pour faire spawn toute les unit�s en stock", (arg, player) -> {
				if (stock.get(player.team()) == 0) {
					Players.err(player, "Le vote ne peut pas commenc� car votre stock d'unit� est vide !");
					return;
				} 
				if (inVote.get(player.team())) {
					Players.err(player, "Un vote est d�j� en cours !");
					return;
				}
				if (cooldowns.get(player.team()) != null) {
					Players.err(player, "Veuillez attendre encore [green]" + createDate(cooldowns.get(player.team()).getTime()) + "[] avant de relancer un vote.");
					return;
				}
				
				UnitType search = content.units().find(b -> b.name.equals(arg[0]));
					
				if (search == null) {
					Players.err(player, "Cette unit� n'existe pas! []Unit�s disponible :");
					player.sendMessage(content.units().toString("[scarlet], []"));
					
				} else {
					voted.add(player.uuid());
					inVote.put(player.team(), true);
					unit.put(player.team(), search);
					sessions.put(player.team(), new Votes(player.team()));
						
					Players.messageToTeam(player.team(), "%s[orange] a lanc� un vote pour le spawn de [white]%s %s [lightgray]", 
						NetClient.colorizeName(player.id, player.name), stock, search.name);
				}
				
			});
		
			handler.<Player>register("plugin", "<help|commande> [valeur]", "[scarlet][[Admin][] Configuration du plugin", (arg, player) -> {
				if (!Players.adminCheck(player)) return;
				
				StringBuilder builder = new StringBuilder();
				switch (arg[0]) {
					default: Players.err(player, "Arguments invalides ! []Arguments possibles :");
					case "help":
						builder.append(" [orange]- help[lightgray] : Affiche l'aide des arguments possibles.\n");
						builder.append(" [orange]- settime [white]<minutes>[lightgray] : Red�fini le temps avant nouvelle unit�. (min: 1, max: 1440)\n");
						builder.append(" [orange]- setduration [white]<minutes>[lightgray] : Red�fini la dur�e d'un vote. (min: 1, max: 60)\n");
						builder.append(" [orange]- setcooldown [white]<minutes>[lightgray] : Red�fini le temps d'attente entre les votes. (min: 1, max: 60)\n");
						builder.append(" [orange]- forcespawn [white]<unit�>[lightgray] : Fait spawn toutes les unit�s en stock de votre team sans passer par un vote.\n");
						builder.append(" [orange]- resetstock [white][[team][lightgray] : R�initialise le stock d'unit� d'une team ou toutes si pas d'argument.\n");
						builder.append(" [orange]- resetvotes [white][[team][lightgray] : R�initialise les votes d'une team ou toutes si pas d'argument.\n");
						builder.append(" [orange]- resettime[lightgray] : R�initialise le temps avant nouvelle unit�.\n");
						builder.append(" [orange]- delcooldown [white][[team][lightgray] : Supprime le temps d'attente entre les votes, d'une team ou toutes si pas d'argument.\n");
						builder.append(" [orange]- default[lightgray] : Remet toutes les valeurs par d�faut.\n");
						builder.append(" [orange]- info[lightgray] : Affiche les informations de la partie en cours et du plugin.\n");
						player.sendMessage(builder.toString());
						break;
					
					case "settime":
						if (arg.length == 2) {
							if (arc.util.Strings.canParseInt(arg[1])) {
								int valeur = Integer.parseInt(arg[1]);
								
								if (valeur < 1 || valeur > 1440) Players.err(player, "La valeur doit �tre comprise entre 1 (1min) et 1440 (1j).");
								else {
									time = valeur;
									timer = false;
									Call.announce("[green]Le temps avant nouvelle unit� a �t� red�fifni � [scarlet]" + createDate(time*60) + " [lightgray](par " + player.name + "[lightgray])");
									Call.sendMessage("\n[orange]/!\\ [green]Le temps avant nouvelle unit� a �t� red�fifni � [scarlet]" + createDate(time*60) + " [lightgray](par " + player.name + "[lightgray])\n");
									startTimer();
								}
							} else Players.err(player, "La valeur doit �tre un chiffre !");
						} else Players.err(player, "Veuillez entrer une valeur !");
						break;
						
					case "setduration":
						if (arg.length == 2) {
							if (arc.util.Strings.canParseInt(arg[1])) {
								int valeur = Integer.parseInt(arg[1]);
								
								if (valeur < 1 || valeur > 60) Players.err(player, "La valeur doit �tre comprise entre 1 (1min) et 60 (1h).");
								else {
									duration = valeur;
									timer = false;
									Call.announce("[green]La dur�e d'un vote a �t� red�fini � [scarlet]" + createDate(duration*60) + " [lightgray](par " + player.name + "[lightgray])");
									Call.sendMessage("\n[orange]/!\\ [green]La dur�e d'un vote a �t� red�fini � [scarlet]" + createDate(duration*60) + " [lightgray](par " + player.name + "[lightgray])\n");
									startTimer();
								}
							} else Players.err(player, "La valeur doit �tre un chiffre !");
						} else Players.err(player, "Veuillez entrer une valeur !");
						break;
						
					case "setcooldown":
						if (arg.length == 2) {
							if (arc.util.Strings.canParseInt(arg[1])) {
								int valeur = Integer.parseInt(arg[1]);
								
								if (valeur < 1 || valeur > 60) Players.err(player, "La valeur doit �tre comprise entre 1 (1min) et 60 (1h).");
								else {
									cooldown = valeur;
									timer = false;
									Call.announce("[green]Le temps d'attente entre les votes a �t� red�fini � [scarlet]" + createDate(cooldown*60) + " [lightgray](par " + player.name + "[lightgray])");
									Call.sendMessage("\n[orange]/!\\ [green]Le temps d'attente entre les votes a �t� red�fini � [scarlet]" + createDate(cooldown*60) + " [lightgray](par " + player.name + "[lightgray])\n");
									startTimer();
								}
							} else Players.err(player, "La valeur doit �tre un chiffre !");
						} else Players.err(player, "Veuillez entrer une valeur !");
						break;
					
					case "forcespawn":
						if (arg.length == 2) {
							UnitType search = content.units().find(b -> b.name.equals(arg[0]));
							
							if (search == null) {
								Players.err(player, "Cette unit� n'existe pas! []Unit�s disponible :");
								player.sendMessage(content.units().toString("[scarlet], []"));
							} else {
								startSpawn(player.team(), search);
								Players.messageToTeam(player.team(), "%s [orange]a forc� le spawn de [white] %s %s", player.name, stock.get(player.team()), unit.get(player.team()).name);
							}
						} else Players.err(player, "Veuillez entrer une valeur !");
						break;
					
					case "resetstock":
						if (arg.length == 2) {
							if (!Players.testGamemode()) {
								Players.err(player, "Indisponible dans ce mode de jeu !");
								return;
							}
							Team target = Players.findTeam(arg[1], player);
							if (target == null) return;
							stock.put(target, 0);
							Players.messageToTeam(target, "[orange]Votre stock d'unit� a �t� r�initialis�. [lightgray](par " + player.name + "[lightgray])");
							
						} else {
							for (Team team : Team.baseTeams) stock.put(team, 0);
							Call.announce("[scarlet]Le stock d'unit� " + (Players.testGamemode() ? "de toutes les teams " : "") + "a �t� r�initialis�. [lightgray](par " + player.name + "[lightgray])");
							Call.sendMessage("\n[orange]/!\\ [scarlet]Le stock d'unit� " + (Players.testGamemode() ? "de toutes les teams " : "") + "a �t� r�initialis�. [lightgray](par " + player.name + "[lightgray])\n");
						}
						break;
						
					case "resetvotes":
						if (arg.length == 2) {
							if (!Players.testGamemode()) {
								Players.err(player, "Indisponible dans ce mode de jeu !");
								return;
							}
							Team target = Players.findTeam(arg[1], player);
							if (target == null) return;
							
							votes.put(target, 0);
							inVote.put(target, false);
							Groups.player.each(p -> p.team().equals(target), p -> voted.remove(p.uuid()));
							Players.messageToTeam(target, "[orange]Tous vos votes ont �t� r�initialis�s. [lightgray](par " + player.name + "[lightgray])");
							
						} else {
							for (Team team : Team.baseTeams) {
								votes.put(team, 0);
								inVote.put(team, false);
							}
							voted.clear();
							Call.announce("[scarlet]Les votes " + (Players.testGamemode() ? "de toutes les teams " : "") + "ont �t� r�initialis�s. [lightgray](par " + player.name + "[lightgray])");
							Call.sendMessage("\n[orange]/!\\ [scarlet]Les votes " + (Players.testGamemode() ? "de toutes les teams " : "") + "ont �t� r�initialis�s. [lightgray](par " + player.name + "[lightgray])\n");
						}
						break;
					
					case "resettime":
						timer = false;
						Call.announce("[scarlet]Le temps avant nouvelle unit� a �t� r�initialis�. [lightgray](par " + player.name + "[lightgray])");
						Call.sendMessage("\n[orange]/!\\ [scarlet]Le temps avant nouvelle unit� a �t� r�initialis�. [lightgray](par " + player.name + "[lightgray])\n");
						startTimer();
						break;
						
					case "delcooldown":
						if (arg.length == 2) {
							if (!Players.testGamemode()) {
								Players.err(player, "Indisponible dans ce mode de jeu !");
								return;
							}
							Team target = Players.findTeam(arg[1], player);
							if (target == null) return;
							
							Votes c = cooldowns.get(player.team());
							if (c == null) Players.err(player, "Cette team n'a pas de temps d'attente !");
							else {
								c.stopVotes();
								cooldowns.remove(player.team());
								Players.messageToTeam(target, "[orange]Votre temps d'attente entre les votes a �t� r�initialis�. [lightgray](par " + player.name + "[lightgray])");
							}
							
							
						} else {
							cooldowns.forEach(t -> t.value.stopVotes());
							cooldowns.clear();
							Call.announce("[scarlet]Le temps d'attente entre les votes " + (Players.testGamemode() ? "de toutes les teams " : "") + "a �t� r�initialis�. [lightgray](par " + player.name + "[lightgray])");
							Call.sendMessage("\n[orange]/!\\ [scarlet]Le temps d'attente entre les votes " + (Players.testGamemode() ? "de toutes les teams " : "") + "a �t� r�initialis�. [lightgray](par " + player.name + "[lightgray])\n");
						}
						break;
					
					case "default":
						init();
						timer = false;
						time = 30;
						duration = 2;
						cooldown = 5;
						saveSettings();
						Call.announce("[scarlet]Le plugin a �t� entierement r�initialis�. [lightgray](par " + player.name + "[lightgray])");
						Call.sendMessage("\n[orange]/!\\ [scarlet]Le plugin a �t� entierement r�initialis�. [lightgray](par " + player.name + "[lightgray])\n");
						startTimer();
						break;
					
					case "info":
						Seq<String> temp = new Seq<>();
						voted.each(v -> temp.add(Players.findByID(v).player.name));
						builder.append("[orange]Joueurs qui ont vot�s : []" + (temp.isEmpty() ? "<empty>" : temp.toString("[white], ")));
						if (Players.testGamemode()) {
							for (Team team : Team.baseTeams) {
								builder.append("- [royal]" + team.name + " :[] [green]" + stock.get(team) + "[] unit�(s) en stock | En Vote : [green]" 
										+ (inVote.get(team) ? "oui[] | Nombre de vote : [green]" + votes.get(team) : "non") 
										+ "[] | Unit� choisi : [green]" + (unit.get(player.team()) != null ? unit.get(team).name : "<unknown>"));
							}
							builder.append("\n[orange]Informations des teams :[]\n" + temp.toString("\n[white]"));
						} else 
							builder.append("\n[orange]Informations de la partie :[]\n"
									+ "- [green]" + stock.get(player.team()) + "[] unit�(s) en stock | En Vote : [green]" 
									+ (inVote.get(player.team()) ? "oui[] | Nombre de vote : [green]" + votes.get(player.team()) : "non") 
									+ "[] | Unit� choisi : [green]" + (unit.get(player.team()) != null ? unit.get(player.team()).name : "<unknown>"));
						
						mindustry.mod.Mods.LoadedMod mod = mindustry.Vars.mods.getMod("unitsforall-plugin");
						builder.append("\n[orange]Informations du plugin :[white]"
								+ "\n- Nom : " + mod.meta.displayName()
								+ "\n- Version : " + mod.meta.version
								+ "\n- Auteur : " + mod.meta.author
								+ "\n- Chemin : " + mod.file.path()
								+ "\n- Description : " + mod.meta.description);
						Call.infoMessage(player.con, builder.toString());
						break;
				}
			});
			
			handler.<Player>register("test", "test", (arg, player) -> {
				new mindustry.ai.WaveSpawner().spawnEnemies();
			});
		}
	}
	
	private void loadSettings() {
		if (settings.has("UnitForAll")) {
			try {
				String[] save = settings.getString("UnitForAll").split(" \\| ");
				isActivated = Boolean.parseBoolean(save[0]);
				time = Integer.parseInt(save[1]);
				duration = Integer.parseInt(save[2]);
				cooldown = Integer.parseInt(save[3]);
			} catch (Exception e) {
				saveSettings();
				loadSettings();
			};
			
		} else saveSettings();
	}
	
	private void saveSettings() {
		settings.put("UnitForAll", String.join(" | ", isActivated+"", time+"", duration+"", cooldown+""));
		settings.forceSave();
	}
	
	private void startSpawn(Team team, UnitType unit) {
		int count = stock.get(team);
		stock.put(team, 0);
        inVote.put(team, false);
		votes.put(team, 0);
		if (sessions.containsKey(team)) {
			sessions.get(team).stopVotes();
			sessions.remove(team);
		}
		
		//#################################################################################
	}
	
	private void startTimer() {
		if (isActivated) {
			timer = true;
		
			new Thread() {
				public void run() {
					String text = "[orange] Unit�s en stock : [green]%s[]\nTemps avant nouvelle\nunit� : [green]%s[]%s";
					int sec = time*60;
				
					while (timer) {
						try {
							if (sec-- <= 0) {
								for (Team team : Team.baseTeams) stock.put(team, stock.get(team)+1);
								Call.announce("[green]Nouvelle unit� en stock !");
								sec = time*60;
							}
							
							final String restTime = createDate(sec);
							
							Groups.player.each(p -> Call.infoPopup(p.con, String.format(text, 
									stock.get(p.team()), restTime, (sessions.get(p.team()) != null ? 
											"\nTemps de vote \nrestant : [green]" + createDate(sessions.get(p.team()).timeLeft) 
											+ "\n[]Unit� choisi : [green]" + unit.get(p.team()).name
											+ "\n[]Nombre de vote : [green]" + votes.get(p.team()): 
											cooldowns.get(p.team()) != null ? "\nProchain vote possible\n     dans : [green]" + cooldowns.get(p.team()).getTime() : "")), 
								(float) 1.002, 17, 0, 0, 0, 0)
							);
							Thread.sleep(1000);
						} catch (InterruptedException e) {}
					}
				}
			}.start();
		}	
	}
	
	private String createDate(int sec) {
		  int h = sec / 60 / 60 % 24;
		  int m = sec / 60 % 60;
		  int s = sec % 60;
		  String output = "";
		  
		  if (h != 0) output += h + "h ";
		  if (m != 0) output += m + "min ";
		  if (s != 0) output += s + "sec ";
		  
		  return output.strip();
	}
	
	 
	public static class Votes {
		private Timer.Task task = null;
		public int timeLeft;
		
		public Votes(Team team) { new Votes(team, false); }
		public Votes(Team team, boolean inCooldown) {
			if (inCooldown) this.timeLeft = cooldown*60;
			else this.timeLeft = duration*60;
			
			this.task = Timer.schedule(() -> {
				try {
					this.timeLeft--;
					
					if (this.timeLeft <= 0) {
						if (!inCooldown) {
							Players.messageToTeam(team, "[scarlet]Temps �coul� ! Le vote pour faire spawn [accent]" + stock.get(team) + " " + unit.get(team).name + "[] est annul�.");
							cooldowns.put(team, new Votes(team, true));
						} else {
							Players.messageToTeam(team, "[green]Temps d'attente termin� ! Vous pouvez � nouveau lancer un vote.");
							cooldowns.remove(team);
						}
						this.task.cancel();
					}
					Thread.sleep(1000);
				} catch (InterruptedException e) {}
			}, timeLeft+2);
			this.task.run();
		}
		
		public void stopVotes() {
			if (this.task != null || this.task.isScheduled()) this.task.cancel();
		}
		
		public Integer getTime() {
			if (this.task == null || !this.task.isScheduled()) return null;
			else return this.timeLeft;
		}
	}
}
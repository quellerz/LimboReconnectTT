/*
 * Copyright (C) 2022 - 2025 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limboreconnect.handler;

import static net.elytrium.limboreconnect.LimboReconnect.CONFIG;

import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboreconnect.Config;
import net.elytrium.limboreconnect.LimboReconnect;
import net.elytrium.limboreconnect.protocol.packets.PlaySound;

public class ReconnectHandler implements LimboSessionHandler {

  private final PingOptions pingOptions = PingOptions.builder().timeout(Duration.ofMillis(CONFIG.pingTimeout)).build();
  private final LimboReconnect plugin;
  private final RegisteredServer server;
  private LimboPlayer player;
  private boolean connected = true;
  private boolean connecting = false;
  private int titleIndex = -1;
  private final PlaySound waitSound;
  private final PlaySound connectSound;
  private boolean connectSoundPlayed = false; // Says if the sounds was already played | говорит был ли уже проигран звук
  private long lastWaitingPlayAt = 0L;

  public ReconnectHandler(LimboReconnect plugin, RegisteredServer server) {
    this.plugin = plugin;
    this.server = server;
    Config.Sounds sounds = CONFIG.sounds;
    double playerX = CONFIG.world.playerCoords.x;
    double playerY = CONFIG.world.playerCoords.y;
    double playerZ = CONFIG.world.playerCoords.z;
    this.waitSound = new PlaySound(sounds.waiting.name, playerX, playerY, playerZ, sounds.waiting.volume, sounds.waiting.pitch);
    this.connectSound = new PlaySound(sounds.connecting.name, playerX, playerY, playerZ, sounds.connecting.volume, sounds.connecting.pitch);
  }

  @Override
  public void onSpawn(Limbo server, LimboPlayer player) {
    this.player = player;
    this.player.disableFalling();
    this.player.setGameMode(CONFIG.world.gamemode);
    this.player.getScheduledExecutor().schedule(this::tick, CONFIG.checkInterval, TimeUnit.MILLISECONDS);
    this.tickMessages();

    // Гарантированно запускаем первую мелодию с небольшой задержкой, чтобы клиент был готов
    this.player.getScheduledExecutor().schedule(() -> {
      if (!this.connected || this.connecting) {
        return;
      }
      String name = CONFIG.sounds.waiting.name;
      if (name != null && !name.isEmpty()) {
        this.player.writePacket(this.waitSound);
        this.lastWaitingPlayAt = System.currentTimeMillis();
      }
      // Запускаем цикл повторов, если задана длительность
      this.scheduleWaitingLoop();
    }, 1, TimeUnit.SECONDS);
  }

  @Override
  public void onDisconnect() {
    this.connected = false;
    this.connectSoundPlayed = false; // Resets sound counter | Ресетаем счётчик звуков
    this.lastWaitingPlayAt = 0L;
  }

  @Override
  public void onMove(double x, double y, double z) {
    this.waitSound.setPosition(x, y, z);
    this.connectSound.setPosition(x, y, z);
  }

  private void tick() {
    if (!this.connected) {
      return;
    }

    this.server.ping(this.pingOptions).whenComplete((ping, exception) -> {
      if (exception != null) {
        if (CONFIG.debug) {
          LimboReconnect.getLogger()
              .info("{} can't ping {}", this.player.getProxyPlayer().getGameProfile().getName(), this.server.getServerInfo().getName());
        }

        this.player.getScheduledExecutor().schedule(this::tick, CONFIG.checkInterval, TimeUnit.MILLISECONDS);
      } else {
        this.player.getScheduledExecutor().execute(() -> {
          this.connecting = true;
          this.titleIndex = -1;
          this.player.getScheduledExecutor().schedule(() -> {
            if (!connectSoundPlayed) {
              this.player.writePacket(this.connectSound);
              this.connectSoundPlayed = true;
            }
            this.player.getProxyPlayer().resetTitle();
            this.player.disconnect(this.server);
          }, CONFIG.joinDelay, TimeUnit.MILLISECONDS);
        });
      }
    });
  }

  private void tickMessages() {
    if (!this.connected) {
      return;
    }

    if (this.connecting) {
      this.titleIndex = (this.titleIndex + 1) % this.plugin.connectingTitles.size();
      this.player.getProxyPlayer().showTitle(this.plugin.connectingTitles.get(this.titleIndex));
    } else {
      this.titleIndex = (this.titleIndex + 1) % this.plugin.offlineTitles.size();
      this.player.getProxyPlayer().showTitle(this.plugin.offlineTitles.get(this.titleIndex));
    }
    this.player.getScheduledExecutor().schedule(this::tickMessages, CONFIG.messages.titles.showDelay * 50, TimeUnit.MILLISECONDS);
  }

  private void scheduleWaitingLoop() {
    if (!this.connected) {
      return;
    }

    if (this.connecting) {
      return;
    }

    long baseLen = CONFIG.sounds.waiting.lengthMs;
    float pitch = CONFIG.sounds.waiting.pitch;
    long len = baseLen > 0 ? (long) (baseLen / Math.max(0.01f, pitch)) : 0L;
    if (len <= 0L) {
      // Нет повторов — сыграли один раз при входе и выходим
      return;
    }

    long now = System.currentTimeMillis();
    long nextAt = (this.lastWaitingPlayAt == 0L) ? now : (this.lastWaitingPlayAt + len);
    if (now + 25L >= nextAt) {
      // Время переигрывать
      String name = CONFIG.sounds.waiting.name;
      if (name != null && !name.isEmpty()) {
        this.player.writePacket(this.waitSound);
        this.lastWaitingPlayAt = now;
        nextAt = this.lastWaitingPlayAt + len;
      }
    }

    long delayMs = Math.max(250L, nextAt - now);
    this.player.getScheduledExecutor().schedule(this::scheduleWaitingLoop, delayMs, TimeUnit.MILLISECONDS);
  }
}

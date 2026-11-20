package com.laevatein;

/** 所有玩法数值。时间单位都是 tick（20 tick = 1 秒），距离单位是格。 */
public final class LaevateinConfig {
    private LaevateinConfig() {}

    // 完全解放莱瓦汀：60 秒叠到 99 层，满层触发 10 格火焰灼烧光环。
    public static final int HEAT_MAX_TICKS = 1200;
    public static final int HEAT_MAX_LAYERS = 99;
    public static final double HEAT_AURA_RADIUS = 10.0;
    public static final double HEAT_AURA_RADIUS_SQR = HEAT_AURA_RADIUS * HEAT_AURA_RADIUS;
    public static final int HEAT_DAMAGE_INTERVAL = 20;
    public static final float HEAT_DAMAGE = 1.0f;

    // 拆壳
    public static final int UNSHELL_TICKS_PER_LAYER = 20;      // 每层 1 秒，三层共 3 秒
    public static final int SEAL_HITS_PER_LAYER = 8;         // 玩家：每层 8 次
    public static final int MATTHIAS_SEAL_HITS_PER_LAYER = 5; // 马蒂亚斯：每层 5 次
    public static final int FLING_DELAY = 10;                  // 甩飞动画 0.5 秒后才真正移除这一层

    // 封印态斜劈
    public static final float SEAL_SLASH_DAMAGE = 7f;
    public static final double SEAL_SLASH_RANGE = 3.5;
    public static final double SEAL_SLASH_CONE = 70;
    public static final float SEAL_HEAVY_MULT = 2f;
    public static final int SEAL_SLASH_CD = 9;
    public static final int SEAL_HEAVY_CD = 16;

    // 本体大力劈砍（沿用封印态的斜劈方向与特效）
    public static final float HEAVY_SLASH_DAMAGE = 14f;
    public static final double HEAVY_SLASH_RANGE = 4.5;
    public static final double HEAVY_SLASH_CONE = 75;
    // 大力劈砍 4 段连携：右斜劈、反向斜劈、横向重斩、双手终结下劈。
    // 冷却与动画长度匹配；实际受击帧见 IMPACT_TICKS，避免按键即伤害的穿帮。
    public static final int[] HEAVY_COMBO_COOLDOWNS = {12, 12, 13, 18};
    public static final int[] HEAVY_COMBO_IMPACT_TICKS = {5, 5, 6, 9};
    public static final float[] HEAVY_COMBO_DAMAGE_MULTIPLIERS = {0.8f, 0.9f, 1.0f, 1.5f};
    public static final int HEAVY_COMBO_RESET_TICKS = 34;

    // 连招：仅在收招前 8 tick 内缓存下一次左键
    public static final int COMBO_BUFFER_TICKS = 8;

    // 重新包装：和现有 reshell 资源时长一致
    public static final int RESHELL_TICKS = 60;

    // 本体横扫
    public static final float SWEEP_DAMAGE = 10f;
    public static final double SWEEP_RANGE = 4.5;
    public static final double SWEEP_CONE = 160;
    public static final int SWEEP_CD = 11;

    // 穿刺
    public static final int PIERCE_HITS = 6;
    public static final int PIERCE_INTERVAL = 2;
    public static final float PIERCE_DAMAGE = 4f;
    public static final double PIERCE_RANGE = 6;

    // 竖劈
    public static final float CHOP_DAMAGE = 14f;
    public static final double CHOP_LENGTH = 5;
    public static final double CHOP_WIDTH = 1.5;
    public static final int CHOP_CD = 14;

    // 投掷处决
    public static final float THROW_SPEED = 2.5f;
    public static final float THROW_IMPACT_DAMAGE = 8f;
    // Matthias only: steer toward his locked target at each server tick.
    // Player-launched projectiles keep vanilla physics and their original accuracy.
    public static final double MATTHIAS_THROW_TRACK_SPEED = 3.2;
    public static final double MATTHIAS_THROW_HIT_TOLERANCE = 0.45;
    public static final int THROW_RELEASE_DELAY = 5;           // 和投掷手臂动作的出手帧对齐
    public static final int BURN_INTERVAL = 2;
    public static final float BURN_DAMAGE = 2f;
    public static final int BURN_MAX = 100;
    public static final int BLADE_MAX_FLIGHT = 60;             // 飞行 3 秒没扎中就自动召回
    public static final int BLADE_MAX_LIFE = 400;              // 扎在墙上 20 秒后自动召回
    public static final double RECALL_SPEED = 1.6;

    // V 键：手动四段连踹。每次物理按下只触发下一脚；停按即停在当前段。
    public static final int QUAD_KICK_COOLDOWN = 220;           // 新连招 11 秒 CD；有效连段内不受它阻止
    public static final int QUAD_KICK_COMBO_WINDOW = 42;       // 前一脚结束后 2.1 秒可按 V 续段
    public static final int QUAD_KICK_RECOVERY_TICKS = 4;      // 单脚收招后短暂防止普通攻击穿模
    public static final double QUAD_KICK_TARGET_RANGE = 5.0;
    public static final double QUAD_KICK_FOLLOWUP_ACQUIRE_RANGE = 7.0;
    public static final double QUAD_KICK_REACH = 3.1;
    public static final double QUAD_KICK_TRACK_MAX_RANGE = 14.0;
    public static final double QUAD_KICK_CHASE_SPEED = 0.68;    // 只控制水平短冲刺，不在每 tick 抬升玩家
    public static final double QUAD_KICK_CHASE_HOP = 0.26;      // 仅在有高度差的追踪第一 tick 触发
    public static final int QUAD_KICK_CHASE_MAX_TICKS = 10;
    public static final int[] QUAD_KICK_IMPACT_TICKS = {5, 4, 4, 6};
    public static final int[] QUAD_KICK_STRIKE_TICKS = {10, 9, 9, 12};
    public static final float[] QUAD_KICK_DAMAGE = {5f, 7f, 7f, 10f};
    // 第一脚上挑、第二脚大击退、第三脚追踹、第四脚下砸；覆盖目标速度，不累加旧冲量。
    public static final double[] QUAD_KICK_FORWARD_FORCE = {0.36, 1.35, 0.85, 0.32};
    public static final double[] QUAD_KICK_VERTICAL_FORCE = {0.72, 0.16, 0.12, -1.15};

    // 投掷处决的飞踹
    public static final double DASH_SPEED = 1.8;
    public static final int DASH_TIMEOUT_MIN = 10;
    public static final double KICK_REACH = 2.2;
    public static final float KICK_DAMAGE = 8f;
    public static final double KICK_KNOCKBACK = 2.5;
    public static final int NO_FALL_AFTER_DASH = 40;
}

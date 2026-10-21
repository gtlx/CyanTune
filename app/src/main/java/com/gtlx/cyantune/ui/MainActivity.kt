/*
 * CyanTune 青调 —— 合并设置页
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * 简洁 Material Light 单页：
 *  - 总开关
 *  - 状态栏漂移分组：开关 + 幅度 px(2–8 SeekBar) + 周期间隔秒(10–300 SeekBar)
 *  - 桌面 180° 旋转分组：开关
 *  - 保存按钮（root 写两处），底部 root 状态与生效提示
 *
 * 刻意不做花哨视觉（Soft UI 非本单重点），系统 Material 主题 + 代码布局，零额外资源依赖。
 */
package com.gtlx.cyantune.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.gtlx.cyantune.R
import com.gtlx.cyantune.core.ConfigStore
import com.gtlx.cyantune.core.RootBridge
import com.gtlx.cyantune.core.TuneConfig

class MainActivity : Activity() {

    // 编辑中的配置（初始默认值，onCreate 时读回覆盖）
    private var globalEnabled = true
    private var driftEnabled = true
    private var driftPx = 4
    private var driftIntervalSec = 60
    private var rotation180Enabled = true
    private var pocketEnabled = true
    private var pocketUseLightGate = false

    private lateinit var rootStatus: TextView
    private lateinit var saveBtn: Button
    private lateinit var restartSystemUiBtn: Button
    private lateinit var restartLauncherBtn: Button
    private lateinit var pxLabel: TextView
    private lateinit var intervalLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadConfig()
        setContentView(buildContent())
    }

    // ===== 布局 =====

    private fun buildContent(): ScrollView {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        // 标题
        container.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0E7490"))
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.ui_subtitle)
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, dp(2), 0, dp(12))
        })

        // 总开关
        container.addView(sectionTitle(getString(R.string.section_global)))
        container.addView(switchCard(
            getString(R.string.global_enabled_title),
            getString(R.string.global_enabled_desc),
            globalEnabled
        ) { globalEnabled = it })

        // 状态栏漂移
        container.addView(sectionTitle(getString(R.string.section_statusbar)))
        container.addView(switchCard(
            getString(R.string.drift_enabled_title),
            getString(R.string.drift_enabled_desc),
            driftEnabled
        ) { driftEnabled = it })

        pxLabel = TextView(this).apply {
            text = getString(R.string.drift_px, driftPx)
            setTextColor(Color.parseColor("#334155"))
            textSize = 13f
            setPadding(dp(4), dp(10), 0, dp(2))
        }
        container.addView(pxLabel)
        container.addView(seekBar(2, 8, driftPx) {
            driftPx = it
            pxLabel.text = getString(R.string.drift_px, it)
        })

        intervalLabel = TextView(this).apply {
            text = getString(R.string.drift_interval, driftIntervalSec)
            setTextColor(Color.parseColor("#334155"))
            textSize = 13f
            setPadding(dp(4), dp(10), 0, dp(2))
        }
        container.addView(intervalLabel)
        container.addView(seekBar(10, 300, driftIntervalSec) {
            driftIntervalSec = it
            intervalLabel.text = getString(R.string.drift_interval, it)
        })

        // 桌面 180° 旋转
        container.addView(sectionTitle(getString(R.string.section_launcher)))
        container.addView(switchCard(
            getString(R.string.rotation180_enabled_title),
            getString(R.string.rotation180_enabled_desc),
            rotation180Enabled
        ) { rotation180Enabled = it })

        // 口袋防误触
        container.addView(sectionTitle(getString(R.string.section_pocket)))
        container.addView(switchCard(
            getString(R.string.pocket_enabled_title),
            getString(R.string.pocket_enabled_desc),
            pocketEnabled
        ) { pocketEnabled = it })
        container.addView(switchCard(
            getString(R.string.pocket_light_gate_title),
            getString(R.string.pocket_light_gate_desc),
            pocketUseLightGate
        ) { pocketUseLightGate = it })
        container.addView(TextView(this).apply {
            text = getString(R.string.pocket_hint)
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(4), dp(6), dp(2), 0)
        })

        // 保存按钮
        saveBtn = Button(this).apply {
            text = getString(R.string.btn_save)
            setOnClickListener { saveConfig() }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
            layoutParams = lp
        }
        container.addView(saveBtn)

        // 底部状态
        rootStatus = TextView(this).apply {
            textSize = 12f
            setPadding(dp(2), dp(14), dp(2), 0)
        }
        container.addView(rootStatus)
        container.addView(TextView(this).apply {
            text = getString(R.string.footer_hint)
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(2), dp(6), dp(2), 0)
        })

        // 重启目标进程（改 hook 后应用生效的常用手段）
        container.addView(sectionTitle(getString(R.string.section_restart)))
        container.addView(TextView(this).apply {
            text = getString(R.string.restart_warn)
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(2), 0, dp(2), dp(8))
        })
        restartSystemUiBtn = Button(this).apply {
            text = getString(R.string.btn_restart_systemui)
            setOnClickListener {
                runRootRestart(
                    this,
                    "killall com.android.systemui",
                    getString(R.string.restarting_systemui),
                    getString(R.string.btn_restart_systemui)
                )
            }
        }
        container.addView(restartSystemUiBtn)
        restartLauncherBtn = Button(this).apply {
            text = getString(R.string.btn_restart_launcher)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = lp
            setOnClickListener {
                runRootRestart(
                    this,
                    "am force-stop com.android.launcher3",
                    getString(R.string.restarting_launcher),
                    getString(R.string.btn_restart_launcher)
                )
            }
        }
        container.addView(restartLauncherBtn)

        refreshRootStatus()
        scroll.addView(container)
        return scroll
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#0F172A"))
        setPadding(dp(2), dp(18), dp(2), dp(8))
    }

    private fun switchCard(title: String, desc: String, checked: Boolean,
                           onChange: (Boolean) -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = cardBg()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1F2937"))
        })
        col.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(3), dp(8), 0)
        })
        val sw = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, b -> onChange(b) }
        }
        row.addView(col)
        row.addView(sw)
        card.addView(row)
        return card
    }

    private fun seekBar(min: Int, max: Int, value: Int, onChange: (Int) -> Unit): SeekBar {
        // SeekBar 原生不支持 min<API26，minSdk 31 可直接用
        return SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = value.coerceIn(min, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onChange(p)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    private fun cardBg() = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(Color.WHITE)
        setStroke(dp(1), Color.parseColor("#E2E8F0"))
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    // ===== 配置读写 =====

    private fun currentConfig() = TuneConfig(
        globalEnabled = globalEnabled,
        driftEnabled = driftEnabled,
        driftPx = driftPx,
        driftIntervalSec = driftIntervalSec,
        rotation180Enabled = rotation180Enabled,
        pocketEnabled = pocketEnabled,
        pocketUseLightGate = pocketUseLightGate,
    )

    private fun loadConfig() {
        // 读回当前生效值：ConfigStore 内部优先 tmp 副本，失败读 filesDir，再失败默认值
        val cfg = try {
            ConfigStore.loadForUi(applicationContext)
        } catch (_: Throwable) {
            TuneConfig.DEFAULT
        }
        globalEnabled = cfg.globalEnabled
        driftEnabled = cfg.driftEnabled
        driftPx = cfg.driftPx
        driftIntervalSec = cfg.driftIntervalSec
        rotation180Enabled = cfg.rotation180Enabled
        pocketEnabled = cfg.pocketEnabled
        pocketUseLightGate = cfg.pocketUseLightGate
    }

    private fun saveConfig() {
        saveBtn.isEnabled = false
        saveBtn.text = getString(R.string.saving)
        val cfg = currentConfig()
        Thread {
            val ok = try {
                ConfigStore.saveFromUi(applicationContext, cfg)
                true
            } catch (t: Throwable) {
                false
            }
            runOnUiThread {
                saveBtn.isEnabled = true
                saveBtn.text = getString(R.string.btn_save)
                refreshRootStatus()
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.save_ok) else getString(R.string.save_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    /**
     * 后台线程执行 root 重启命令；执行期间两个重启按钮 disable + 文案变“正在重启…”，
     * 完成后 Toast 提示；失败明确提示可能未授 root。
     */
    private fun runRootRestart(btn: Button, command: String, runningText: String, idleText: String) {
        restartSystemUiBtn.isEnabled = false
        restartLauncherBtn.isEnabled = false
        btn.text = runningText
        Thread {
            val ok = try {
                RootBridge.exec(command)
                true
            } catch (t: Throwable) {
                false
            }
            runOnUiThread {
                btn.text = idleText
                restartSystemUiBtn.isEnabled = true
                restartLauncherBtn.isEnabled = true
                refreshRootStatus()
                Toast.makeText(
                    this,
                    if (ok) getString(R.string.restart_ok) else getString(R.string.restart_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun refreshRootStatus() {
        if (!::rootStatus.isInitialized) return
        if (RootBridge.isRootAvailable) {
            rootStatus.text = "🔓 " + getString(R.string.root_status_ok)
            rootStatus.setTextColor(Color.parseColor("#059669"))
        } else {
            rootStatus.text = "⚠️ " + getString(R.string.root_status_no)
            rootStatus.setTextColor(Color.parseColor("#DC2626"))
        }
    }
}

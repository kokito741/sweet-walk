/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.tiefensuche.motionmate.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import android.view.View
import android.view.ViewGroup
import android.widget.CalendarView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.tiefensuche.motionmate.R
import com.tiefensuche.motionmate.service.MotionService
import com.tiefensuche.motionmate.ui.cards.MotionActivityTextItem
import com.tiefensuche.motionmate.ui.cards.MotionStatisticsTextItem
import com.tiefensuche.motionmate.ui.cards.MotionTextItem
import com.tiefensuche.motionmate.util.Database
import com.tiefensuche.motionmate.util.Util
import java.util.*
import kotlin.collections.ArrayList

/**
 * The main activity for the UI of the step counter.
 */
internal class MainActivity : AppCompatActivity() {
    private lateinit var mTextViewSteps: TextView
    private lateinit var mTextViewMeters: TextView
    private lateinit var mTextViewCalendarContent: TextView
    private lateinit var mCalendarView: CalendarView
    private lateinit var mChart: Chart
    private var stepCount = 0
    private var points = 0
    private lateinit var mTextViewChart: TextView
    private var mAdapter: TextItemAdapter = TextItemAdapter()
    private lateinit var mMonthlyStepsCard: MotionStatisticsTextItem
    private lateinit var mAverageStepsCard: MotionTextItem
    private lateinit var mOverallStepsCard: MotionStatisticsTextItem
    private var mCurrentSteps: Int = 0
    private var mSelectedMonth = Util.calendar
    private val handler = Handler()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stepCount = 0
        points = 0
        Util.applyTheme(PreferenceManager.getDefaultSharedPreferences(this).getString("theme", "system")!!)
        DynamicColors.applyToActivitiesIfAvailable(application)
        createNotificationChannel()
        scheduleNotifications()//// Schedule the notification with a random delay
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        Util.stepWidth = PreferenceManager.getDefaultSharedPreferences(this).getInt("step_width", 70)

        mTextViewSteps = findViewById(R.id.textViewSteps)
        mTextViewMeters = findViewById(R.id.textViewMeters)
        val mRecyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        mTextViewCalendarContent = findViewById(R.id.textViewCalendarContent)
        mChart = findViewById(R.id.chart)
        mTextViewChart = findViewById(R.id.textViewChart)
        mCalendarView = findViewById(R.id.calendar)
        mCalendarView.minDate = Database.getInstance(this).firstEntry.let {
            if (it == 0L)
                Util.calendar.timeInMillis
            else
                it
        }
        mCalendarView.maxDate = Util.calendar.timeInMillis
        mCalendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            mSelectedMonth.set(Calendar.YEAR, year)
            mSelectedMonth.set(Calendar.MONTH, month)
            mSelectedMonth.set(Calendar.DAY_OF_MONTH, dayOfMonth)

            updateChart()
            updateCards()
        }

        val mLayoutManager = LinearLayoutManager(this)
        mRecyclerView.layoutManager = mLayoutManager
        mRecyclerView.adapter = mAdapter

        // setup swipeable cards and remove them on swiped, used for activities
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView,
                                viewHolder: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val item = mAdapter[viewHolder.adapterPosition]
                if (item is MotionActivityTextItem) {
                    val i = Intent(this@MainActivity, MotionService::class.java)
                    i.action = MotionService.ACTION_STOP_ACTIVITY
                    i.putExtra(MotionService.KEY_ID, item.id)
                    startService(i)
                    mAdapter.remove(viewHolder.adapterPosition)
                }
            }

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val swipeFlags = ItemTouchHelper.START or ItemTouchHelper.END
                return if (mAdapter[viewHolder.adapterPosition].isSwipeable) {
                    ItemTouchHelper.Callback.makeMovementFlags(0, swipeFlags)
                } else 0
            }

        }).attachToRecyclerView(mRecyclerView)

        // Floating action button to start new activity
        findViewById<View>(R.id.fab).let {
            it.setOnClickListener {
                val i = Intent(this@MainActivity, MotionService::class.java)
                i.action = MotionService.ACTION_START_ACTIVITY
                startService(i)
            }

            ViewCompat.setOnApplyWindowInsetsListener(it) { view, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = insets.bottom + 50
                }
                WindowInsetsCompat.CONSUMED
            }
        }

        mTextViewSteps.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // initial update of the diagram
        updateChart()

        // Add some cards with statistics
        setupCards()

        // Start the motion service
        checkPermission()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "PictureNotificationChannel"
            val descriptionText = "Channel for picture notifications$points"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("picture_notification_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    private fun scheduleNotifications() {
        val randomInterval = (20..50).random() * 1000L // Random interval between 2 to 5 minutes * 60
        handler.postDelayed({
            showNotification()
            scheduleNotifications() // Reschedule after the notification is shown
        }, randomInterval)
    }
    private fun showNotification() {

        val builder = NotificationCompat.Builder(this, "picture_notification_channel")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Time to take a picture!")
            .setContentText("Capture a moment during your activity. Points : $points")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(this)) {
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            notify(0, builder.build())
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        AppCompatDelegate.setDefaultNightMode(
            if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES)
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else AppCompatDelegate.MODE_NIGHT_YES)
        return super.onOptionsItemSelected(item)
    }

    private fun setupCards() {
        // A card that displays sum of steps in the current month
        mMonthlyStepsCard = MotionStatisticsTextItem(this, R.string.steps_month, 0)
        mAdapter.add(mMonthlyStepsCard)

        // A card that displays average distance in a day
        mAverageStepsCard = MotionTextItem(this, R.string.avg_distance)
        mAdapter.add(mAverageStepsCard)

        // A card that displays overall sum of steps
        val overallSteps = Database.getInstance(this).getSumSteps(Database.getInstance(this).firstEntry, Database.getInstance(this).lastEntry)
        mOverallStepsCard = MotionStatisticsTextItem(this, R.string.overall_distance, overallSteps)
        mAdapter.add(mOverallStepsCard)

        updateCards()
    }

    private fun subscribeService() {
        // start the service and pass a result receiver that is used by the service to update the UI
        val i = Intent(this, MotionService::class.java)
        i.action = MotionService.ACTION_SUBSCRIBE
        i.putExtra(RECEIVER_TAG, object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                if (resultCode == 0) {
                    runOnUiThread { updateView(resultData.getInt(MotionService.KEY_STEPS), resultData.getParcelableArrayList(MotionService.KEY_ACTIVITIES) ?: ArrayList()) }
                }
            }
        })
        startService(i)
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), 0)
        } else {
            subscribeService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        subscribeService()
    }

    private fun updateView(steps: Int, activities: MutableList<Bundle>) {
        // update current today's steps in the header
        mCurrentSteps = steps
        mTextViewMeters.text = String.format(getString(R.string.meters_today), Util.stepsToMeters(steps))
        mTextViewSteps.text = resources.getQuantityString(R.plurals.steps_text, steps, steps)
        createNotificationChannel()

        if (steps % 10 == 0) {
            points++ // Add 1 point for every 10 steps
        }

        // update calendar max date for the case that new day started
        if (!DateUtils.isToday(mCalendarView.maxDate))
            mCalendarView.maxDate = Util.calendar.timeInMillis

        // update the cards
        mOverallStepsCard.updateSteps(steps)
        for (i in 0 until mAdapter.itemCount) {
            val item = mAdapter[i]
            if (item is MotionActivityTextItem) {
                for (activity in activities) {
                    if (activity.getInt(MotionService.KEY_ID) == item.id) {
                        item.updateSteps(activity.getInt(MotionService.KEY_STEPS))
                        activities.remove(activity)
                        break
                    }
                }
            }
        }

        // initialize dynamic cards, e.g. activities, that are not yet added
        for (activity in activities) {
            val id = activity.getInt(MotionService.KEY_ID)
            val item = MotionActivityTextItem(this, id, View.OnClickListener {
                val i = Intent(this@MainActivity, MotionService::class.java)
                i.action = MotionService.ACTION_TOGGLE_ACTIVITY
                i.putExtra(MotionService.KEY_ID, id)
                startService(i)
            })
            item.updateSteps(activity.getInt(MotionService.KEY_STEPS))
            item.setActive(activity.getBoolean(MotionService.KEY_ACTIVE))
            mAdapter.addTop(item)
        }

        // If selected week is the current week, update the diagram and cards with today's steps
        if (mSelectedMonth.get(Calendar.WEEK_OF_YEAR) == Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)) {
            mChart.setCurrentSteps(steps)
            mChart.update()
            mMonthlyStepsCard.updateSteps(steps)
        }
    }

    private fun updateChart() {
        val min = Calendar.getInstance()
        min.timeInMillis = mSelectedMonth.timeInMillis

        // Jump to the first day of the week
        min.set(Calendar.DAY_OF_WEEK, Calendar.getInstance().firstDayOfWeek)

        val max = Calendar.getInstance()
        max.timeInMillis = min.timeInMillis

        // Jump to the last day of the week
        max.add(Calendar.DAY_OF_YEAR, 6)

        mChart.clearDiagram()
        mTextViewChart.text = String.format(Locale.getDefault(), getString(R.string.week_display_format), min.get(Calendar.WEEK_OF_YEAR), min.timeInMillis, max.timeInMillis)

        // Get the records of the selected week between the min and max timestamps
        val entries = Database.getInstance(this).getEntries(min.timeInMillis, max.timeInMillis)

        mTextViewCalendarContent.text = String.format(getString(R.string.no_entry), mSelectedMonth.timeInMillis)
        for (entry in entries) {
            mChart.setDiagramEntry(entry)

            val cal = Calendar.getInstance()
            cal.timeInMillis = entry.timestamp

            // Update the description text with the selected date
            if (cal.get(Calendar.DAY_OF_WEEK) == mSelectedMonth.get(Calendar.DAY_OF_WEEK)) {
                mTextViewCalendarContent.text = String.format(Locale.getDefault(), getString(R.string.steps_day_display), cal.timeInMillis, Util.stepsToMeters(entry.steps), entry.steps)
            }
        }

        // If selected week is the current week, update the diagram with today's steps
        if (mSelectedMonth.get(Calendar.WEEK_OF_YEAR) == Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)) {
            mChart.setCurrentSteps(mCurrentSteps)
        }
        mChart.update()
    }

    private fun updateCards() {
        val cal = Util.calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)

        val startOfMonth = Calendar.getInstance()
        startOfMonth.timeInMillis = mSelectedMonth.timeInMillis
        startOfMonth.set(Calendar.DAY_OF_MONTH, 1)
        val currentMonth = cal == startOfMonth

        val endOfMonth = Calendar.getInstance()
        endOfMonth.timeInMillis = startOfMonth.timeInMillis
        endOfMonth.add(Calendar.MONTH, 1)

        val stepsThisMonth = Database.getInstance(this).getSumSteps(startOfMonth.timeInMillis, endOfMonth.timeInMillis)
        mMonthlyStepsCard.initialSteps = stepsThisMonth
        mMonthlyStepsCard.updateSteps(if (currentMonth) mCurrentSteps else 0)
        mAverageStepsCard.setContent(Database.getInstance(this).avgSteps(startOfMonth.timeInMillis, endOfMonth.timeInMillis))
    }

    companion object {

        const val RECEIVER_TAG = "RECEIVER_TAG"
    }
}

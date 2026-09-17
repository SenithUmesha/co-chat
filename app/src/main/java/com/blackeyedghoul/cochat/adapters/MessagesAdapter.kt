package com.blackeyedghoul.cochat.adapters

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.blackeyedghoul.cochat.Chat
import com.blackeyedghoul.cochat.R
import com.blackeyedghoul.cochat.models.Conversation
import java.text.SimpleDateFormat
import java.util.Date

class MessagesAdapter(
    private val conversationsList: ArrayList<Conversation>,
    private val context: Context
) : RecyclerView.Adapter<MessagesAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.home_user_card, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("SimpleDateFormat")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = conversationsList[position]
        val room = conversation.room
        val receiver = conversation.receiver
        val sender = conversation.sender

        holder.fullName.text = receiver.username
        holder.message.text = room.lastMessage

        room.lastUpdatedTimestamp?.toDate()?.let { messageDate ->
            val dayFormat = SimpleDateFormat("dd/MM/yyyy")
            val isToday = dayFormat.format(Date()) == dayFormat.format(messageDate)
            holder.time.text = if (isToday) {
                SimpleDateFormat("HH:mm").format(messageDate)
            } else {
                SimpleDateFormat("MMM d").format(messageDate)
            }
        } ?: run {
            holder.time.text = ""
        }

        holder.status.visibility = if (receiver.isOnline) View.VISIBLE else View.INVISIBLE
        holder.profilePicture.setImageResource(profilePictureResource(receiver.profilePicture))

        holder.itemView.setOnClickListener {
            val intent = Intent(context, Chat::class.java).apply {
                putExtra("RECEIVER", receiver)
                putExtra("SENDER", sender)
                putExtra("ROOM_ID", room.id)
                putExtra("ROOM", room)
            }
            context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int = conversationsList.size

    private fun profilePictureResource(profilePicture: String): Int {
        return when (profilePicture) {
            "01" -> R.drawable.pp_1
            "02" -> R.drawable.pp_2
            "03" -> R.drawable.pp_3
            "04" -> R.drawable.pp_4
            "05" -> R.drawable.pp_5
            "06" -> R.drawable.pp_6
            "07" -> R.drawable.pp_7
            "08" -> R.drawable.pp_8
            "09" -> R.drawable.pp_9
            "10" -> R.drawable.pp_10
            "11" -> R.drawable.pp_11
            "12" -> R.drawable.pp_12
            "13" -> R.drawable.pp_13
            "14" -> R.drawable.pp_14
            "15" -> R.drawable.pp_15
            "16" -> R.drawable.pp_16
            else -> R.drawable.pp_1
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val fullName: TextView = itemView.findViewById(R.id.h_user_card_full_name)
        val time: TextView = itemView.findViewById(R.id.h_user_card_time)
        val profilePicture: ImageView = itemView.findViewById(R.id.h_user_card_profile_picture)
        val message: TextView = itemView.findViewById(R.id.h_user_card_last_message)
        val status: CardView = itemView.findViewById(R.id.h_user_card_profile_picture_online_icon)
    }
}

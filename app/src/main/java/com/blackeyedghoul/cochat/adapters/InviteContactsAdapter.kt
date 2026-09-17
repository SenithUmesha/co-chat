package com.blackeyedghoul.cochat.adapters

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blackeyedghoul.cochat.R
import com.blackeyedghoul.cochat.models.Contact

class InviteContactsAdapter(
    private val inviteUsersList: ArrayList<Contact>,
    private val context: Context
) : RecyclerView.Adapter<InviteContactsAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.invite_user_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = inviteUsersList[position]
        holder.fullName.text = user.name

        holder.invite.setOnClickListener {
            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Hey ${user.name} 👋🏼 wanna try out CoChat? It's a simple chat app I built as a Kotlin side project. https://github.com/SenithUmesha/co-chat"
                )
                type = "text/plain"
            }

            context.startActivity(Intent.createChooser(intent, "Share"))
        }
    }

    override fun getItemCount(): Int = inviteUsersList.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val fullName: TextView = itemView.findViewById(R.id.c_invite_user_card_full_name)
        val invite: Button = itemView.findViewById(R.id.c_invite_user_card_button)
    }
}

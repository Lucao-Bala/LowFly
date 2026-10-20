package com.example.rotasmaisentregas

import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ParadaAdapter(
    private val paradas: MutableList<Parada>,
    private val onItemChanged: () -> Unit
) : RecyclerView.Adapter<ParadaAdapter.ParadaViewHolder>() {

    class ParadaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val chkConcluida: CheckBox = view.findViewById(R.id.chkConcluida)
        val lblAppTag: TextView = view.findViewById(R.id.lblAppTag)
        val txtEndereco: EditText = view.findViewById(R.id.txtEndereco)
        val btnRemover: ImageButton = view.findViewById(R.id.btnRemover)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParadaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_parada, parent, false)
        return ParadaViewHolder(view)
    }

    override fun onBindViewHolder(holder: ParadaViewHolder, position: Int) {
        val item = paradas[position]

        holder.lblAppTag.text = if (item.appOrigem.isNotBlank()) item.appOrigem else "APP"
        holder.chkConcluida.isChecked = item.concluida
        atualizarEstiloTexto(holder.txtEndereco, item.concluida)

        // Evitar chamadas infinitas de TextWatcher durante o rebind
        holder.txtEndereco.tag = item.id
        holder.txtEndereco.setText(item.endereco)

        holder.chkConcluida.setOnCheckedChangeListener { _, isChecked ->
            item.concluida = isChecked
            atualizarEstiloTexto(holder.txtEndereco, isChecked)
            onItemChanged()
        }

        holder.btnRemover.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION && currentPos < paradas.size) {
                paradas.removeAt(currentPos)
                notifyItemRemoved(currentPos)
                onItemChanged()
            }
        }

        holder.txtEndereco.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (holder.txtEndereco.tag == item.id) {
                    item.endereco = s?.toString() ?: ""
                    onItemChanged()
                }
            }
        })
    }

    private fun atualizarEstiloTexto(editText: EditText, concluida: Boolean) {
        if (concluida) {
            editText.paintFlags = editText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            editText.alpha = 0.5f
        } else {
            editText.paintFlags = editText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            editText.alpha = 1.0f
        }
    }

    override fun getItemCount(): Int = paradas.size
}
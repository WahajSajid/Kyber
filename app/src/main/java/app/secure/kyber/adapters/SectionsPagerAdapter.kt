package app.secure.kyber.adapters

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import app.secure.kyber.fragments.DocumentsFragment
import app.secure.kyber.fragments.LinksFragment
import app.secure.kyber.fragments.MediaFragment

class SectionsPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> MediaFragment()
            1 -> LinksFragment()
            2 -> DocumentsFragment()
            else -> throw IndexOutOfBoundsException("Invalid position $position")
        }
    }
}
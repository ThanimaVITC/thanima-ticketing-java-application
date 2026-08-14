package com.legitcoconut.thanimaticketing.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayoutMediator;
import com.legitcoconut.thanimaticketing.R;
import com.legitcoconut.thanimaticketing.databinding.FragmentAttendeesBinding;
import com.legitcoconut.thanimaticketing.databinding.ItemRegistrationBinding;
import com.legitcoconut.thanimaticketing.databinding.PageAttendeesBinding;
import com.legitcoconut.thanimaticketing.model.Registration;
import com.legitcoconut.thanimaticketing.net.Api;
import com.legitcoconut.thanimaticketing.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Read only view of the same registrations. All, Present and Absent are three pages of a
 * pager, so they can be swiped between as well as tapped, and the search field filters
 * whichever page is showing.
 */
public class AttendeesFragment extends Fragment {

    private static final String ARG_EVENT_ID = "eventId";
    private static final String ARG_EVENT_TITLE = "eventTitle";

    private static final int TAB_ALL = 0;
    private static final int TAB_PRESENT = 1;
    private static final int TAB_ABSENT = 2;
    private static final int PAGES = 3;

    public static AttendeesFragment newInstance(String eventId, String eventTitle) {
        AttendeesFragment f = new AttendeesFragment();
        Bundle args = new Bundle();
        args.putString(ARG_EVENT_ID, eventId);
        args.putString(ARG_EVENT_TITLE, eventTitle);
        f.setArguments(args);
        return f;
    }

    private FragmentAttendeesBinding binding;
    private String eventId;

    private final List<Registration> all = new ArrayList<>();

    /** One list and one adapter per page, indexed by tab. */
    private final List<List<Registration>> pageItems = new ArrayList<>();
    private final Adapter[] adapters = new Adapter[PAGES];
    private final PageAttendeesBinding[] pages = new PageAttendeesBinding[PAGES];

    private String query = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        binding = FragmentAttendeesBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        eventId = requireArguments().getString(ARG_EVENT_ID);

        Ui.toolbar(this, binding.toolbar, getString(R.string.attendees));

        pageItems.clear();
        for (int i = 0; i < PAGES; i++) {
            pageItems.add(new ArrayList<>());
            adapters[i] = new Adapter(pageItems.get(i));
            pages[i] = null;
        }

        binding.pager.setAdapter(new PagerAdapter());
        new TabLayoutMediator(binding.tabLayout, binding.pager,
                (tab, position) -> tab.setText(tabTitle(position))).attach();

        binding.etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                recompute();
            }
        });

        binding.swipeRefresh.setOnRefreshListener(this::load);

        Api.EventDetails cached = Api.peek("event:" + eventId);
        if (cached != null) render(cached);
        load();
    }

    private String tabTitle(int position) {
        int presentCount = 0;
        for (Registration r : all) if (r.attended) presentCount++;
        switch (position) {
            case TAB_PRESENT:
                return getString(R.string.att_tab_present_fmt, presentCount);
            case TAB_ABSENT:
                return getString(R.string.att_tab_absent_fmt, all.size() - presentCount);
            default:
                return getString(R.string.att_tab_all_fmt, all.size());
        }
    }

    private void updateTabTitles() {
        for (int i = 0; i < PAGES; i++) {
            com.google.android.material.tabs.TabLayout.Tab tab = binding.tabLayout.getTabAt(i);
            if (tab != null) tab.setText(tabTitle(i));
        }
    }

    private void load() {
        Api.getEventDetails(eventId, (details, error) -> {
            if (binding == null) return;
            binding.swipeRefresh.setRefreshing(false);
            if (error != null) {
                Ui.error(binding.getRoot(), error);
                return;
            }
            render(details);
        });
    }

    private void render(Api.EventDetails details) {
        all.clear();
        all.addAll(details.registrations);
        updateTabTitles();
        recompute();
    }

    /** Page filter, then the search filter on top of it, sorted alphabetically. */
    private void recompute() {
        for (int page = 0; page < PAGES; page++) {
            List<Registration> items = pageItems.get(page);
            items.clear();
            for (Registration r : all) {
                if (page == TAB_PRESENT && !r.attended) continue;
                if (page == TAB_ABSENT && r.attended) continue;
                if (!r.matches(query)) continue;
                items.add(r);
            }
            Collections.sort(items, (a, b) -> a.name.compareToIgnoreCase(b.name));
            adapters[page].notifyDataSetChanged();
            updateEmptyState(page);
        }
    }

    private void updateEmptyState(int page) {
        PageAttendeesBinding p = pages[page];
        if (p == null) return;
        boolean empty = pageItems.get(page).isEmpty();
        p.recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        p.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) p.tvEmpty.setText(emptyMessage(page));
        else Ui.animateList(p.recyclerView);
    }

    private int emptyMessage(int page) {
        if (!query.isEmpty()) return R.string.no_match;
        switch (page) {
            case TAB_PRESENT:
                return R.string.att_present_empty;
            case TAB_ABSENT:
                return R.string.att_absent_empty;
            default:
                return R.string.nobody_here;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        for (int i = 0; i < PAGES; i++) pages[i] = null;
        binding = null;
    }

    // ------------------------------------------------------------------ pager

    /**
     * Three pages, each its own view type, so a page view is only ever bound to the position
     * it was created for and its list adapter never has to be swapped.
     */
    private class PagerAdapter extends RecyclerView.Adapter<PagerAdapter.PageVH> {

        class PageVH extends RecyclerView.ViewHolder {
            PageVH(PageAttendeesBinding p) {
                super(p.getRoot());
            }
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @NonNull
        @Override
        public PageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            PageAttendeesBinding p = PageAttendeesBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false);
            p.recyclerView.setLayoutManager(new LinearLayoutManager(parent.getContext()));
            p.recyclerView.setAdapter(adapters[viewType]);
            pages[viewType] = p;
            return new PageVH(p);
        }

        @Override
        public void onBindViewHolder(@NonNull PageVH holder, int position) {
            updateEmptyState(position);
        }

        @Override
        public int getItemCount() {
            return PAGES;
        }
    }

    // ------------------------------------------------------------------ rows

    private static class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        private final List<Registration> items;

        Adapter(List<Registration> items) {
            this.items = items;
        }

        static class VH extends RecyclerView.ViewHolder {
            final ItemRegistrationBinding b;

            VH(ItemRegistrationBinding b) {
                super(b.getRoot());
                this.b = b;
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(ItemRegistrationBinding.inflate(
                    LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            ItemRegistrationBinding b = holder.b;
            Registration r = items.get(position);

            String letter = r.name.isEmpty() ? "?" : r.name.substring(0, 1).toUpperCase(Locale.getDefault());
            b.tvAvatar.setText(letter);
            b.tvName.setText(r.name);
            b.tvMeta.setText(r.regNo + "  •  " + r.email);

            // Read only: never clickable, chevron never shown.
            b.ivChevron.setVisibility(View.GONE);
            b.getRoot().setOnClickListener(null);
            b.getRoot().setClickable(false);
            b.getRoot().setFocusable(false);

            if (r.attended) {
                b.ivTick.setVisibility(View.VISIBLE);
                b.tvTime.setVisibility(View.VISIBLE);
                b.tvTime.setText(Ui.formatTime(r.markedAt));
            } else {
                b.ivTick.setVisibility(View.GONE);
                b.tvTime.setVisibility(View.GONE);
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}

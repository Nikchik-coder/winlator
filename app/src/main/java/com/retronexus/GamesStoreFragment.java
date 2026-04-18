package com.retronexus;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import android.widget.TextView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.retronexus.core.Game;
import com.retronexus.core.ImageLoader;
import com.retronexus.core.SupabaseClient;

import java.util.ArrayList;
import java.util.List;

public class GamesStoreFragment extends Fragment {
    private GridView gridView;
    private GamesAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.games_store_fragment, container, false);
        gridView = view.findViewById(R.id.GridView);
        adapter = new GamesAdapter();
        gridView.setAdapter(adapter);

        if (getActivity() instanceof AppCompatActivity) {
            ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle("RetroNexus");
        }

        loadGames();

        gridView.setOnItemClickListener((parent, view1, position, id) -> {
            Game game = adapter.getItem(position);
            MainActivity activity = (MainActivity) getActivity();
            activity.showFragment(new GameDetailFragment(game));
        });

        return view;
    }

    private void loadGames() {
        SupabaseClient.fetchGames(new SupabaseClient.Callback<List<Game>>() {
            @Override
            public void onSuccess(List<Game> games) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        adapter.setGames(games);
                    });
                }
            }

            @Override
            public void onError(Exception e) {
                if (getActivity() != null) {
                    String msg = e != null && e.getMessage() != null ? e.getMessage() : "Unknown error";
                    if (msg.length() > 220) msg = msg.substring(0, 220);
                    String toastText = "Failed to load games: " + msg;
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), toastText, Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    private class GamesAdapter extends BaseAdapter {
        private List<Game> games = new ArrayList<>();

        public void setGames(List<Game> games) {
            this.games = games;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return games.size();
        }

        @Override
        public Game getItem(int position) {
            return games.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.game_grid_item, parent, false);
            }

            Game game = getItem(position);
            TextView title = convertView.findViewById(R.id.Title);
            title.setText(game.title);
            
            ImageView thumbnail = convertView.findViewById(R.id.Thumbnail);
            thumbnail.setImageDrawable(null);
            ImageLoader.loadInto(thumbnail, game.thumbnail_url);
            
            return convertView;
        }
    }
}

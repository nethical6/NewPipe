package org.schabi.newpipe.local.bookmark;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import io.reactivex.disposables.Disposable;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.schabi.newpipe.NewPipeDatabase;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.playlist.PlaylistLocalItem;
import org.schabi.newpipe.database.playlist.PlaylistMetadataEntry;
import org.schabi.newpipe.database.playlist.model.PlaylistRemoteEntity;
import org.schabi.newpipe.local.BaseLocalListFragment;
import org.schabi.newpipe.local.dialog.BookmarkDialog;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;
import org.schabi.newpipe.local.playlist.RemotePlaylistManager;
import org.schabi.newpipe.report.UserAction;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.OnClickGesture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import icepick.State;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;

public final class BookmarkFragment
        extends BaseLocalListFragment<List<PlaylistLocalItem>, Void> {

    @State
    protected Parcelable itemsListState;

    private Subscription databaseSubscription;
    private CompositeDisposable disposables = new CompositeDisposable();
    private LocalPlaylistManager localPlaylistManager;
    private RemotePlaylistManager remotePlaylistManager;

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Creation
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (activity == null) return;
        final AppDatabase database = NewPipeDatabase.getInstance(activity);
        localPlaylistManager = new LocalPlaylistManager(database);
        remotePlaylistManager = new RemotePlaylistManager(database);
        disposables = new CompositeDisposable();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             Bundle savedInstanceState) {

        if(!useAsFrontPage) {
            setTitle(activity.getString(R.string.tab_bookmarks));
        }
        return inflater.inflate(R.layout.fragment_bookmarks, container, false);
    }


    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (activity != null && isVisibleToUser) {
            setTitle(activity.getString(R.string.tab_bookmarks));
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Views
    ///////////////////////////////////////////////////////////////////////////

    @Override
    protected void initViews(View rootView, Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);
    }

    @Override
    protected void initListeners() {
        super.initListeners();

        itemListAdapter.setSelectedListener(new OnClickGesture<LocalItem>() {
            @Override
            public void selected(LocalItem selectedItem) {
                final FragmentManager fragmentManager = getFM();

                if (selectedItem instanceof PlaylistMetadataEntry) {
                    final PlaylistMetadataEntry entry = ((PlaylistMetadataEntry) selectedItem);
                    NavigationHelper.openLocalPlaylistFragment(fragmentManager, entry.uid,
                            entry.name);

                } else if (selectedItem instanceof PlaylistRemoteEntity) {
                    final PlaylistRemoteEntity entry = ((PlaylistRemoteEntity) selectedItem);
                    NavigationHelper.openPlaylistFragment(
                            fragmentManager,
                            entry.getServiceId(),
                            entry.getUrl(),
                            entry.getName());
                }
            }

            @Override
            public void held(LocalItem selectedItem) {
                if (selectedItem instanceof PlaylistMetadataEntry) {
                    showLocalDialog((PlaylistMetadataEntry) selectedItem);
                } else if (selectedItem instanceof PlaylistRemoteEntity) {
                    showRemoteDeleteDialog((PlaylistRemoteEntity) selectedItem);
                }
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Loading
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void startLoading(boolean forceLoad) {
        super.startLoading(forceLoad);

        Flowable.combineLatest(
                localPlaylistManager.getPlaylists(),
                remotePlaylistManager.getPlaylists(),
                BookmarkFragment::merge
        ).onBackpressureLatest()
         .observeOn(AndroidSchedulers.mainThread())
         .subscribe(getPlaylistsSubscriber());
    }

    ///////////////////////////////////////////////////////////////////////////
    // Fragment LifeCycle - Destruction
    ///////////////////////////////////////////////////////////////////////////

    @Override
    public void onPause() {
        super.onPause();
        itemsListState = itemsList.getLayoutManager().onSaveInstanceState();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (disposables != null) disposables.clear();
        if (databaseSubscription != null) databaseSubscription.cancel();

        databaseSubscription = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (disposables != null) disposables.dispose();

        disposables = null;
        localPlaylistManager = null;
        remotePlaylistManager = null;
        itemsListState = null;
    }

    ///////////////////////////////////////////////////////////////////////////
    // Subscriptions Loader
    ///////////////////////////////////////////////////////////////////////////

    private Subscriber<List<PlaylistLocalItem>> getPlaylistsSubscriber() {
        return new Subscriber<List<PlaylistLocalItem>>() {
            @Override
            public void onSubscribe(Subscription s) {
                showLoading();
                if (databaseSubscription != null) databaseSubscription.cancel();
                databaseSubscription = s;
                databaseSubscription.request(1);
            }

            @Override
            public void onNext(List<PlaylistLocalItem> subscriptions) {
                handleResult(subscriptions);
                if (databaseSubscription != null) databaseSubscription.request(1);
            }

            @Override
            public void onError(Throwable exception) {
                BookmarkFragment.this.onError(exception);
            }

            @Override
            public void onComplete() {
            }
        };
    }

    @Override
    public void handleResult(@NonNull List<PlaylistLocalItem> result) {
        super.handleResult(result);

        itemListAdapter.clearStreamItemList();

        if (result.isEmpty()) {
            showEmptyState();
            return;
        }

        itemListAdapter.addItems(result);
        if (itemsListState != null) {
            itemsList.getLayoutManager().onRestoreInstanceState(itemsListState);
            itemsListState = null;
        }
        hideLoading();
    }
    ///////////////////////////////////////////////////////////////////////////
    // Fragment Error Handling
    ///////////////////////////////////////////////////////////////////////////

    @Override
    protected boolean onError(Throwable exception) {
        if (super.onError(exception)) return true;

        onUnrecoverableError(exception, UserAction.SOMETHING_ELSE,
                "none", "Bookmark", R.string.general_error);
        return true;
    }

    @Override
    protected void resetFragment() {
        super.resetFragment();
        if (disposables != null) disposables.clear();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Utils
    ///////////////////////////////////////////////////////////////////////////

    private void showRemoteDeleteDialog(final PlaylistRemoteEntity item) {
        showDeleteDialog(item.getName(), remotePlaylistManager.deletePlaylist(item.getUid()));
    }

    private void showLocalDialog(PlaylistMetadataEntry selectedItem) {
        BookmarkDialog dialog = new BookmarkDialog(getContext(),
            selectedItem.name, new BookmarkDialog.OnClickListener() {
            @Override
            public void onDeleteClicked() {
                showDeleteDialog(selectedItem.name,
                    localPlaylistManager.deletePlaylist(selectedItem.uid));
            }

            @Override
            public void onSaveClicked(@NonNull String name) {
                changeLocalPlaylistName(selectedItem.uid, name);
            }
        });
        dialog.show();
    }

    private void showDeleteDialog(final String name, final Single<Integer> deleteReactor) {
        if (activity == null || disposables == null) return;

        new AlertDialog.Builder(activity)
                .setTitle(name)
                .setMessage(R.string.delete_playlist_prompt)
                .setCancelable(true)
                .setPositiveButton(R.string.delete, (dialog, i) ->
                        disposables.add(deleteReactor
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe(ignored -> {/*Do nothing on success*/}, this::onError))
                )
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void changeLocalPlaylistName(long id, String name) {
        if (localPlaylistManager == null) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Updating playlist id=[" + id +
                "] with new name=[" + name + "] items");
        }

        localPlaylistManager.renamePlaylist(id, name);
        final Disposable disposable = localPlaylistManager.renamePlaylist(id, name)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(longs -> {/*Do nothing on success*/}, this::onError);
        disposables.add(disposable);
    }

    private static List<PlaylistLocalItem> merge(final List<PlaylistMetadataEntry> localPlaylists,
                                                 final List<PlaylistRemoteEntity> remotePlaylists) {
        List<PlaylistLocalItem> items = new ArrayList<>(
                localPlaylists.size() + remotePlaylists.size());
        items.addAll(localPlaylists);
        items.addAll(remotePlaylists);

        Collections.sort(items, (left, right) ->
                left.getOrderingName().compareToIgnoreCase(right.getOrderingName()));

        return items;
    }
}


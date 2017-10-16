package org.wordpress.android.ui.themes;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.SearchView;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.wellsql.generated.ThemeModelTable;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.fluxc.model.SiteModel;
import org.wordpress.android.fluxc.model.ThemeModel;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.ToastUtils;

import java.util.List;

/**
 * A fragment for display the results of a theme search
 */
public class ThemeSearchFragment extends ThemeBrowserFragment implements SearchView.OnQueryTextListener,
        MenuItemCompat.OnActionExpandListener {
    public static final String TAG = ThemeSearchFragment.class.getName();
    private static final String BUNDLE_LAST_SEARCH = "BUNDLE_LAST_SEARCH";
    public static final int SEARCH_VIEW_MAX_WIDTH = 10000;

    private List<ThemeModel> mSearchResults;
    private String mLastSearch = "";
    private SearchView mSearchView;
    private MenuItem mSearchMenuItem;

    private SiteModel mSite;

    public static ThemeSearchFragment newInstance(SiteModel site) {
        ThemeSearchFragment fragment = new ThemeSearchFragment();
        Bundle bundle = new Bundle();
        bundle.putSerializable(WordPress.SITE, site);
        fragment.setArguments(bundle);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        if (savedInstanceState != null) {
            mLastSearch = savedInstanceState.getString(BUNDLE_LAST_SEARCH);
        }
        if (savedInstanceState == null) {
            mSite = (SiteModel) getArguments().getSerializable(WordPress.SITE);
        } else {
            mSite = (SiteModel) savedInstanceState.getSerializable(WordPress.SITE);
        }

        if (mSite == null) {
            ToastUtils.showToast(getActivity(), R.string.blog_not_found, ToastUtils.Duration.SHORT);
            getActivity().finish();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(BUNDLE_LAST_SEARCH, mLastSearch);
        outState.putSerializable(WordPress.SITE, mSite);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        menu.removeItem(R.id.menu_search);

        mSearchMenuItem = menu.findItem(R.id.menu_theme_search);
        mSearchMenuItem.expandActionView();
        MenuItemCompat.setOnActionExpandListener(mSearchMenuItem, this);

        configureSearchView();
    }

    @Override
    public boolean onMenuItemActionExpand(MenuItem item) {
        return item.getItemId() == R.id.menu_theme_search;
    }

    @Override
    public boolean onMenuItemActionCollapse(MenuItem item) {
        mThemeBrowserActivity.setIsInSearchMode(false);
        mThemeBrowserActivity.showToolbar();
        mThemeBrowserActivity.getFragmentManager().popBackStack();
        return true;
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        if (!mLastSearch.equals(query)) {
            mLastSearch = query;
            search(query);
        }
        clearFocus(mSearchView);
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        if (!mLastSearch.equals(newText) && !newText.equals("")) {
            mLastSearch = newText;
            search(newText);
        }
        return true;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.theme_search, menu);
    }

    @Override
    public void setRefreshing(boolean refreshing) {
        refreshView();
    }

    @Override
    protected Cursor fetchThemes() {
        return createResultsCursor();
    }

    @Override
    protected void configureSwipeToRefresh(View view) {
        super.configureSwipeToRefresh(view);
        mSwipeToRefreshHelper.setRefreshing(false);
        mSwipeToRefreshHelper.setEnabled(false);
    }

    @Override
    protected void addHeaderViews(LayoutInflater inflater) {
        // No header on Search
    }

    public void setSearchResults(List<ThemeModel> results) {
        mSearchResults = results;
        refreshView();
    }

    private static final String[] CURSOR_COLUMNS = new String[] {
            "_id", ThemeModelTable.NAME, ThemeModelTable.THEME_ID, ThemeModelTable.CURRENCY, ThemeModelTable.PRICE,
            ThemeModelTable.SCREENSHOT_URL, ThemeModelTable.AUTHOR_NAME, ThemeModelTable.IS_WP_COM_THEME
    };

    private Cursor createResultsCursor() {
        if (mSearchResults == null) {
            return mThemeStore.getWpComThemesCursor();
        }

        MatrixCursor cursor = new MatrixCursor(CURSOR_COLUMNS);
        for (ThemeModel theme : mSearchResults) {
            Object[] values = new Object[] {
                    0, theme.getName(), theme.getThemeId(), theme.getCurrency(), theme.getPrice(),
                    theme.getScreenshotUrl(), theme.getAuthorName(), theme.isWpComTheme()
            };
            cursor.addRow(values);
        }
        return cursor;
    }

    private void search(String searchTerm) {
        mLastSearch = searchTerm;

        if (NetworkUtils.isNetworkAvailable(mThemeBrowserActivity)) {
            mThemeBrowserActivity.searchThemes(searchTerm);
        } else {
            refreshView();
        }
    }

    private void configureSearchView() {
        mSearchView = (SearchView) MenuItemCompat.getActionView(mSearchMenuItem);
        mSearchView.setOnQueryTextListener(this);
        mSearchView.setQuery(mLastSearch, true);
        mSearchView.setMaxWidth(SEARCH_VIEW_MAX_WIDTH);
    }

    private void clearFocus(View view) {
        if (view != null) {
            view.clearFocus();
        }
    }
}

package com.newsblur.widget;

import android.content.Intent;
import android.widget.RemoteViewsService;

import com.newsblur.network.APIManager;
import com.newsblur.util.Log;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;

@AndroidEntryPoint
public class WidgetRemoteViewsService extends RemoteViewsService {

    @Inject
    APIManager apiManager;

    private static String TAG = "WidgetRemoteViewsFactory";

    @Override
    public RemoteViewsService.RemoteViewsFactory onGetViewFactory(Intent intent) {
        Log.d(TAG, "onGetViewFactory");
        return new WidgetRemoteViewsFactory(this.getApplicationContext(), intent, apiManager);
    }
}

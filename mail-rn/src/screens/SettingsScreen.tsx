import React from 'react';
import {Text, View} from 'react-native';

export function SettingsScreen(): React.JSX.Element {
  return (
    <View>
      <Text style={{fontSize: 20, fontWeight: '600', marginBottom: 12}}>設定頁</Text>
      <Text>TODO: 語系、通知、帳號設定</Text>
    </View>
  );
}

import React from 'react';
import {Button, Text, View} from 'react-native';

export function LoginScreen({onLoginSuccess}: {onLoginSuccess: () => void}): React.JSX.Element {
  return (
    <View>
      <Text style={{fontSize: 20, fontWeight: '600', marginBottom: 12}}>登入頁</Text>
      <Text style={{marginBottom: 16}}>TODO: 串接帳號密碼 / Passkey 登入流程</Text>
      <Button title="模擬登入並前往首頁" onPress={onLoginSuccess} />
    </View>
  );
}

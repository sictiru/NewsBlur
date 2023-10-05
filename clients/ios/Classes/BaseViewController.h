#import <UIKit/UIKit.h>
#import "MBProgressHUD.h"

@interface BaseViewController : UIViewController

@property (nonatomic, readonly) BOOL isPhone;
@property (nonatomic, readonly) BOOL isMac;
@property (nonatomic, readonly) BOOL isVision;
@property (nonatomic, readonly) BOOL isPortrait;
@property (nonatomic, readonly) BOOL isCompactWidth;

- (void)informError:(id)error;
- (void)informError:(id)error statusCode:(NSInteger)statusCode;
- (void)informMessage:(NSString *)message;
- (void)informLoadingMessage:(NSString *)message;

- (void)addKeyCommandWithInput:(NSString *)input modifierFlags:(UIKeyModifierFlags)modifierFlags action:(SEL)action discoverabilityTitle:(NSString *)discoverabilityTitle;
- (void)addKeyCommandWithInput:(NSString *)input modifierFlags:(UIKeyModifierFlags)modifierFlags action:(SEL)action discoverabilityTitle:(NSString *)discoverabilityTitle wantPriority:(BOOL)wantPriority;
- (void)addCancelKeyCommandWithAction:(SEL)action discoverabilityTitle:(NSString *)discoverabilityTitle;

- (void)updateTheme;

- (void)tableView:(UITableView *)tableView redisplayCellAtIndexPath:(NSIndexPath *)indexPath;
- (void)tableView:(UITableView *)tableView selectRowAtIndexPath:(NSIndexPath *)indexPath animated:(BOOL)animated;
- (void)tableView:(UITableView *)tableView selectRowAtIndexPath:(NSIndexPath *)indexPath animated:(BOOL)animated scrollPosition:(UITableViewScrollPosition)scrollPosition;
- (void)tableView:(UITableView *)tableView deselectRowAtIndexPath:(NSIndexPath *)indexPath animated:(BOOL)animated;

- (void)collectionView:(UICollectionView *)collectionView redisplayCellAtIndexPath:(NSIndexPath *)indexPath;
- (void)collectionView:(UICollectionView *)collectionView selectItemAtIndexPath:(NSIndexPath *)indexPath animated:(BOOL)animated;
- (void)collectionView:(UICollectionView *)collectionView selectItemAtIndexPath:(NSIndexPath *)indexPath animated:(BOOL)animated scrollPosition:(UICollectionViewScrollPosition)scrollPosition;
- (void)collectionView:(UICollectionView *)collectionView deselectItemAtIndexPath:(NSIndexPath *)indexPath animated:(BOOL)animated;

@end

